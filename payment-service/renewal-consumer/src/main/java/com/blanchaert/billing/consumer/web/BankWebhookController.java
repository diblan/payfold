package com.blanchaert.billing.consumer.web;

import com.blanchaert.billing.consumer.config.BankProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
public class BankWebhookController {
    private final BankProperties bankProperties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final Counter accepted;
    private final Counter duplicate;
    private final Counter unauthorized;
    private final Counter rejected;

    public BankWebhookController(BankProperties bankProperties, ObjectMapper objectMapper,
                                 JdbcTemplate jdbc, MeterRegistry meters) {
        this.bankProperties = bankProperties;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.accepted = receivedCounter(meters, "accepted");
        this.duplicate = receivedCounter(meters, "duplicate");
        this.unauthorized = receivedCounter(meters, "unauthorized");
        this.rejected = receivedCounter(meters, "rejected");
    }

    @PostMapping("/webhooks/bank/{bankId}")
    @Transactional
    public ResponseEntity<Void> receive(
            // Explicit name: this module compiles without -parameters, so MVC
            // cannot recover it from reflection (the producer pom differs).
            @PathVariable("bankId") String bankId,
            @RequestHeader(value = "X-Bank-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        if (!bankProperties.id().equals(bankId)) {
            rejected.increment();
            return ResponseEntity.notFound().build();
        }
        if (!validSignature(signature, body)) {
            unauthorized.increment();
            return ResponseEntity.status(401).build();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception exception) {
            rejected.increment();
            return ResponseEntity.badRequest().build();
        }

        String notificationId = text(payload, "notification_id");
        String collectionId = text(payload, "collection_id");
        String outcome = text(payload, "outcome");
        if (notificationId == null || collectionId == null || outcome == null) {
            rejected.increment();
            return ResponseEntity.badRequest().build();
        }

        int inserted = jdbc.update("""
                INSERT INTO settlement_inbox (bank_id, notification_id, payload)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT ON CONSTRAINT uniq_settlement_notification DO NOTHING
                """, bankId, notificationId, new String(body, StandardCharsets.UTF_8));
        if (inserted == 1) {
            accepted.increment();
        } else {
            duplicate.increment();
        }
        return ResponseEntity.ok().build();
    }

    private boolean validSignature(String signature, byte[] body) {
        if (signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    bankProperties.webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private String text(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            return null;
        }
        return value.textValue();
    }

    private Counter receivedCounter(MeterRegistry meters, String result) {
        return Counter.builder("settlement.webhooks.received")
                .description("Bank settlement webhooks by receiver result")
                .tag("result", result)
                .register(meters);
    }
}
