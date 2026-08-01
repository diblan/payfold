package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.bank.BankClient;
import com.blanchaert.billing.consumer.config.RecoveryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Re-querying stale submissions prevents missed pushes from stranding state;
 * deterministic counterparty outcomes make resubmission after amnesia safe.
 * A stored seq-1 notification still marked scheduled is in flight, not
 * missing -- the sweeper leaves it for the webhook so recovered counts stay
 * exact under load.
 */
@Component
@Transactional
public class RecoverySweeper {
    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    private final JdbcTemplate jdbc;
    private final BankClient bank;
    private final RecoveryProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter recovered;
    private final Counter resubmittedSweeps;
    private final Counter recoveredSweeps;
    private final Counter noopSweeps;

    public RecoverySweeper(JdbcTemplate jdbc, BankClient bank,
                           RecoveryProperties properties, ObjectMapper objectMapper,
                           MeterRegistry meters) {
        this.jdbc = jdbc;
        this.bank = bank;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.recovered = Counter.builder("settlements.recovered")
                .description("Settlements synthesized into the inbox by the recovery sweeper")
                .register(meters);
        this.recoveredSweeps = sweepCounter(meters, "recovered");
        this.resubmittedSweeps = sweepCounter(meters, "resubmitted");
        this.noopSweeps = sweepCounter(meters, "noop");
    }

    @Scheduled(fixedDelayString = "${recovery.sweep-interval-ms:60000}")
    public void sweep() {
        sweepOnce();
    }

    @Transactional
    public void sweepOnce() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('recovery-sweeper'))",
                Boolean.class);
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        List<PaymentRow> rows = jdbc.query("""
                        SELECT p.id, p.collection_id, p.bank_id, p.amount_cents, p.currency, p.channel,
                               ch.due_date, c.debtor_iban, c.mandate_reference, c.card_token
                        FROM payment p
                        JOIN charge ch ON ch.id = p.charge_id
                        JOIN subscription s ON s.id = ch.subscription_id
                        JOIN customer c ON c.id = s.customer_id
                        WHERE p.status = 'submitted' AND p.requested_at < now() - (? * interval '1 second')
                        ORDER BY p.requested_at
                        LIMIT 200
                        """,
                (rs, rowNum) -> new PaymentRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("collection_id"),
                        rs.getString("bank_id"),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("channel"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getString("debtor_iban"),
                        rs.getString("mandate_reference"),
                        rs.getString("card_token")),
                properties.staleAfterSeconds());

        for (PaymentRow row : rows) {
            try {
                recover(row);
            } catch (Exception exception) {
                log.warn("Recovery failed for payment {}", row.id(), exception);
            }
        }
    }

    private void recover(PaymentRow row) throws Exception {
        BankClient.CollectionStatus status =
                bank.getCollection(row.bankId(), row.collectionId());
        if (status == null) {
            resubmit(row);
            resubmittedSweeps.increment();
            return;
        }

        BankClient.CollectionStatus.NotificationEntry settlement =
                status.notifications() == null ? null : status.notifications().stream()
                        .filter(notification -> notification.seq() == 1)
                        .findFirst()
                        .orElse(null);
        if (settlement == null) {
            noopSweeps.increment();
            return;
        }

        if ("scheduled".equals(settlement.state())) {
            // An in-flight delivery is neither missing nor received:
            // synthesizing now would race the webhook and inflate the
            // recovered count past the silent cohorts (D19). The delivery
            // envelope is bounded, so this state resolves by the next sweep.
            noopSweeps.increment();
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", 1);
        payload.put("bank_id", row.bankId());
        payload.put("notification_id", settlement.notification_id());
        payload.put("collection_id", row.collectionId());
        payload.put("outcome", settlement.outcome());
        if (settlement.reason() == null) {
            payload.putNull("reason");
        } else {
            payload.put("reason", settlement.reason());
        }
        payload.put("occurred_at", Instant.now().toString());

        int inserted = jdbc.update("""
                INSERT INTO settlement_inbox (bank_id, notification_id, payload)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT ON CONSTRAINT uniq_settlement_notification DO NOTHING
                """, row.bankId(), settlement.notification_id(),
                objectMapper.writeValueAsString(payload));
        if (inserted == 1) {
            recovered.increment();
            recoveredSweeps.increment();
            log.info("Recovered settlement for collection {}", row.collectionId());
        } else {
            noopSweeps.increment();
        }
    }

    private void resubmit(PaymentRow row) {
        String dueDate = row.dueDate().toString();
        if ("CARD".equals(row.channel())) {
            BankClient.CardVerdict verdict = bank.submitCardAuthorization(
                    row.bankId(), row.collectionId(), row.amountCents(),
                    row.currency(), row.cardToken(), dueDate);
            log.info("Resubmitted card authorization for collection {} with authorized={}",
                    row.collectionId(), verdict.authorized());
        } else {
            bank.submitCollection(
                    row.bankId(), row.collectionId(), row.amountCents(), row.currency(),
                    row.debtorIban(), row.mandateReference(), dueDate);
            log.info("Resubmitted collection {}", row.collectionId());
        }
    }

    private Counter sweepCounter(MeterRegistry meters, String result) {
        return Counter.builder("recovery.sweeps")
                .description("Recovery sweep per-payment actions")
                .tag("result", result)
                .register(meters);
    }

    private record PaymentRow(
            UUID id,
            String collectionId,
            String bankId,
            long amountCents,
            String currency,
            String channel,
            LocalDate dueDate,
            String debtorIban,
            String mandateReference,
            String cardToken) {
    }
}
