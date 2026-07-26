package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.model.SettlementReceived;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final Set<String> OUTCOMES = Set.of("settled", "failed", "charged_back");

    private final JdbcTemplate jdbc;
    private final Counter settled;
    private final Counter failed;
    private final Counter chargedBack;
    private final Counter invalid;

    public SettlementService(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.settled = processedCounter(meters, "settled");
        this.failed = processedCounter(meters, "failed");
        this.chargedBack = processedCounter(meters, "charged_back");
        this.invalid = processedCounter(meters, "invalid");
    }

    @Transactional
    public void apply(SettlementReceived event) {
        validate(event);
        Payment payment = jdbc.query("""
                        SELECT id, charge_id, status
                        FROM payment
                        WHERE collection_id = ? AND bank_id = ?
                        """,
                rs -> rs.next()
                        ? new Payment(
                                rs.getObject("id", UUID.class),
                                rs.getObject("charge_id", UUID.class),
                                rs.getString("status"))
                        : null,
                event.collection_id(), event.bank_id());
        if (payment == null) {
            invalid.increment();
            throw invalid(event, "collection was never submitted to this bank");
        }

        if ("charged_back".equals(event.outcome())) {
            // Recorded-fact handling and the payment state machine arrive in R23d;
            // ACKing here keeps the seeded chargeback cohort out of the DLQ meanwhile.
            log.warn("Chargeback handling deferred (bank_id={}, notification_id={}, collection_id={})",
                    event.bank_id(), event.notification_id(), event.collection_id());
            chargedBack.increment();
            return;
        }

        if ("settled".equals(event.outcome())) {
            int updated = jdbc.update("""
                    UPDATE payment
                    SET status = 'succeeded', completed_at = now()
                    WHERE id = ? AND status = 'submitted'
                    """, payment.id());
            if (updated == 1) {
                BillingLinks links = billingLinks(payment.chargeId());
                jdbc.update("UPDATE charge SET status = 'settled' WHERE id = ?",
                        payment.chargeId());
                jdbc.update("UPDATE invoice SET status = 'paid' WHERE id = ?",
                        links.invoiceId());
                jdbc.update("UPDATE subscription SET renewed_at = ? WHERE id = ?",
                        Timestamp.valueOf(links.periodEnd().atTime(9, 0)),
                        links.subscriptionId());
            }
            // A terminal payment makes redelivery a no-op through the status guard.
            settled.increment();
            return;
        }

        jdbc.update("""
                UPDATE payment
                SET status = 'failed', failure_reason = ?, completed_at = now()
                WHERE id = ? AND status = 'submitted'
                """, event.reason(), payment.id());
        failed.increment();
    }

    private void validate(SettlementReceived event) {
        if (event == null
                || blank(event.notification_id())
                || blank(event.collection_id())
                || blank(event.outcome())
                || !OUTCOMES.contains(event.outcome())) {
            invalid.increment();
            throw invalid(event, "required identity or outcome is invalid");
        }
    }

    private BillingLinks billingLinks(UUID chargeId) {
        return jdbc.queryForObject("""
                        SELECT i.period_end, c.subscription_id, c.invoice_id
                        FROM charge c
                        JOIN invoice i ON i.id = c.invoice_id
                        WHERE c.id = ?
                        """,
                (rs, rowNum) -> new BillingLinks(
                        rs.getObject("period_end", LocalDate.class),
                        rs.getObject("subscription_id", UUID.class),
                        rs.getObject("invoice_id", UUID.class)),
                chargeId);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private InvalidSettlementMessageException invalid(
            SettlementReceived event, String detail) {
        return new InvalidSettlementMessageException(
                "Invalid settlement message (notification_id="
                        + (event == null ? null : event.notification_id())
                        + ", collection_id="
                        + (event == null ? null : event.collection_id())
                        + "): " + detail);
    }

    private Counter processedCounter(MeterRegistry meters, String outcome) {
        return Counter.builder("settlements.processed")
                .description("Settlement messages by processing outcome")
                .tag("outcome", outcome)
                .register(meters);
    }

    private record Payment(UUID id, UUID chargeId, String status) {
    }

    private record BillingLinks(
            LocalDate periodEnd, UUID subscriptionId, UUID invoiceId) {
    }
}
