package com.blanchaert.billing.consumer.web;

import com.blanchaert.billing.consumer.config.BankProperties;
import com.blanchaert.billing.consumer.config.BankRegistry;
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
import java.time.Instant;
import java.util.HexFormat;

@RestController
public class BankWebhookController {
    private final BankRegistry bankRegistry;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final Counter accepted;
    private final Counter duplicate;
    private final Counter unauthorized;
    private final Counter stale;
    private final Counter rejected;
    private final long toleranceSeconds;

    public BankWebhookController(BankRegistry bankRegistry, BankProperties bankProperties,
                                 ObjectMapper objectMapper, JdbcTemplate jdbc, MeterRegistry meters) {
        this.bankRegistry = bankRegistry;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.toleranceSeconds = bankProperties.webhookToleranceSeconds();
        this.accepted = receivedCounter(meters, "accepted");
        this.duplicate = receivedCounter(meters, "duplicate");
        this.unauthorized = receivedCounter(meters, "unauthorized");
        this.stale = receivedCounter(meters, "stale");
        this.rejected = receivedCounter(meters, "rejected");
    }

    @PostMapping("/webhooks/bank/{bankId}")
    @Transactional
    public ResponseEntity<Void> receive(
            // Explicit name: this module compiles without -parameters, so MVC
            // cannot recover it from reflection (the producer pom differs).
            @PathVariable("bankId") String bankId,
            @RequestHeader(value = "X-Bank-Signature", required = false) String signature,
            @RequestHeader(value = "X-Bank-Timestamp", required = false) String timestamp,
            @RequestBody byte[] body) {
        BankProperties.BankEntry bank = bankRegistry.byId(bankId);
        if (bank == null) {
            rejected.increment();
            return ResponseEntity.notFound().build();
        }
        // R44: the signature covers "<timestamp>." + body, so the moment is
        // authenticated before it is judged. A missing, malformed, or
        // mismatching signature/timestamp is 401; a genuine signature whose
        // moment lies outside the tolerance is 403 (stale) — a captured
        // webhook cannot be replayed past the window, and nothing here reads
        // the inbox. The consumer's clock is read for authentication only;
        // idempotency and period material still come from message content (G2).
        if (!validSignature(signature, timestamp, body, bank.webhookSecret())) {
            unauthorized.increment();
            return ResponseEntity.status(401).build();
        }
        long sentAt = Long.parseLong(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - sentAt) > toleranceSeconds) {
            stale.increment();
            return ResponseEntity.status(403).build();
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

    private boolean validSignature(String signature, String timestamp, byte[] body, String webhookSecret) {
        if (signature == null || timestamp == null || !timestamp.matches("\\d{1,19}")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
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
