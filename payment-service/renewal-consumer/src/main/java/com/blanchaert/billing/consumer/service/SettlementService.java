package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.config.BankRegistry;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final Set<String> OUTCOMES = Set.of("settled", "failed", "charged_back");
    private static final Set<String> PROCESSED_OUTCOMES =
            Set.of("settled", "failed", "charged_back", "invalid");

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final DunningLifecycle dunningLifecycle;
    private final Map<String, Timer> latencyByBank;

    public SettlementService(
            JdbcTemplate jdbc, MeterRegistry meters, BankRegistry bankRegistry,
            DunningLifecycle dunningLifecycle) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.dunningLifecycle = dunningLifecycle;
        Map<String, Timer> timers = new HashMap<>();
        for (var bank : bankRegistry.entries()) {
            for (String outcome : PROCESSED_OUTCOMES) {
                processedCounter(outcome, bank.id());
            }
            timers.put(bank.id(), Timer.builder("settlements.latency")
                    .description("Bank submission-to-terminal settlement latency")
                    .tag("bank", bank.id())
                    .register(meters));
        }
        processedCounter("invalid", "unknown");
        this.latencyByBank = Map.copyOf(timers);
    }

    @Transactional
    public void apply(SettlementReceived event) {
        validate(event);
        Payment payment = jdbc.query("""
                        SELECT id, charge_id, status, bank_id, requested_at
                        FROM payment
                        WHERE collection_id = ? AND bank_id = ?
                        """,
                rs -> rs.next()
                        ? new Payment(
                                rs.getObject("id", UUID.class),
                                rs.getObject("charge_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("bank_id"),
                                rs.getTimestamp("requested_at").toInstant())
                        : null,
                event.collection_id(), event.bank_id());
        if (payment == null) {
            processedCounter("invalid", "unknown").increment();
            throw invalid(event, "collection was never submitted to this bank");
        }

        if ("charged_back".equals(event.outcome())) {
            // A chargeback wins regardless of arrival order. The bank sends settled
            // before charged_back, but queue redelivery can reorder; applying from
            // 'submitted' too (skipping the never-finalized invoice) keeps the rare
            // reordered case consistent instead of dead-lettering it, and the later
            // settled notification then no-ops on its own status guard.
            int updated = jdbc.update("""
                    UPDATE payment
                    SET status = 'charged_back', failure_reason = ?, charged_back_at = now()
                    WHERE id = ? AND status IN ('submitted', 'succeeded')
                    """, event.reason(), payment.id());
            if (updated == 1) {
                BillingLinks links = billingLinks(payment.chargeId());
                if ("succeeded".equals(payment.status())) {
                    jdbc.update("""
                            UPDATE invoice SET status = 'disputed'
                            WHERE id = ? AND status = 'paid'
                            """, links.invoiceId());
                }
                dunningLifecycle.enterGrace(links.subscriptionId(), event.reason());
            }
            // D16 keeps period math and the charge untouched; R26a moves only the
            // subscription status into its grace lifecycle.
            log.info("Chargeback processed (bank_id={}, notification_id={}, collection_id={})",
                    event.bank_id(), event.notification_id(), event.collection_id());
            processedCounter("charged_back", payment.bankId()).increment();
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
                // The payment's submitted-to-succeeded guard keeps reordered
                // chargebacks and settlement redeliveries out of recovery.
                dunningLifecycle.recoverFromGrace(links.subscriptionId());
                recordLatency(payment);
            }
            // A terminal payment makes redelivery a no-op through the status guard.
            processedCounter("settled", payment.bankId()).increment();
            return;
        }

        int updated = jdbc.update("""
                UPDATE payment
                SET status = 'failed', failure_reason = ?, completed_at = now()
                WHERE id = ? AND status = 'submitted'
                """, event.reason(), payment.id());
        if (updated == 1) {
            recordLatency(payment);
            BillingLinks links = billingLinks(payment.chargeId());
            dunningLifecycle.enterGrace(links.subscriptionId(), event.reason());
        }
        processedCounter("failed", payment.bankId()).increment();
    }

    private void validate(SettlementReceived event) {
        if (event == null
                || blank(event.notification_id())
                || blank(event.collection_id())
                || blank(event.outcome())
                || !OUTCOMES.contains(event.outcome())) {
            processedCounter("invalid", "unknown").increment();
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

    private Counter processedCounter(String outcome, String bank) {
        return meters.counter(
                "settlements.processed", "outcome", outcome, "bank", bank);
    }

    private void recordLatency(Payment payment) {
        latencyByBank.get(payment.bankId()).record(
                Duration.between(payment.requestedAt(), Instant.now()));
    }

    private record Payment(
            UUID id, UUID chargeId, String status, String bankId, Instant requestedAt) {
    }

    private record BillingLinks(
            LocalDate periodEnd, UUID subscriptionId, UUID invoiceId) {
    }
}
