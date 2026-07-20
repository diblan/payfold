package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.psp.PspChargeOutcome;
import com.blanchaert.billing.consumer.psp.PspClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate jdbc;
    private final PspClient psp;
    private final Counter processedSucceeded;
    private final Counter processedFailed;
    private final Counter processedInvalid;

    public BillingService(JdbcTemplate jdbc, PspClient psp, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.psp = psp;
        this.processedSucceeded = processedCounter(meters, "succeeded");
        this.processedFailed = processedCounter(meters, "failed");
        this.processedInvalid = processedCounter(meters, "invalid");
    }

    public void process(RenewalRequested evt) {
        try {
            validate(evt);
        } catch (InvalidRenewalMessageException e) {
            processedInvalid.increment();
            throw e;
        }

        LocalDate dueDate = LocalDate.parse(evt.due_date());
        LocalDate ps = LocalDate.parse(evt.period_start());
        LocalDate pe = LocalDate.parse(evt.period_end());
        String idem = evt.idempotency_key();
        // 2) Upsert invoice
        UUID invoiceId = upsertInvoice(evt.customer_id(), ps, pe, evt.amount_cents(), evt.currency());
        // 3) Upsert charge linked to subscription + invoice + due_date
        UUID chargeId = upsertCharge(evt.subscription_id(), invoiceId, evt.amount_cents(), evt.currency(), dueDate);
        // 4) Create payment row (pending) guarded by idempotency unique key
        UUID paymentId = upsertPayment(idem, chargeId, evt.amount_cents(), evt.currency());
        // 5) Call the PSP only for a pending payment; failed payments are terminal.
        String status = jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
        if ("failed".equals(status)) {
            // Terminal: dunning is a non-goal (D5); redelivery must not re-attempt the charge.
            processedFailed.increment();
            return;
        }
        if ("pending".equals(status)) {
            PspChargeOutcome outcome = psp.charge(idem, evt.subscription_id(), evt.amount_cents(), evt.currency());
            if (!outcome.succeeded()) {
                markPaymentFailed(paymentId);
                log.info("Payment failed for {}: {}", idem, outcome.reason());
                processedFailed.increment();
                return;
            }
            markPaymentSucceeded(paymentId);
        }
        // 6) Finalize only a succeeded payment; failed outcomes return above unfinalized.
        finalizeBilling(invoiceId, chargeId, evt.subscription_id(), pe);
        processedSucceeded.increment();
    }

    private Counter processedCounter(MeterRegistry meters, String outcome) {
        return Counter.builder("renewals.processed")
                .description("Renewal messages by processing outcome")
                .tag("outcome", outcome)
                .register(meters);
    }

    private void validate(RenewalRequested evt) {
        if (evt.event_id() == null) {
            throw invalid(evt, "event_id", "must not be null");
        }
        if (evt.subscription_id() == null) {
            throw invalid(evt, "subscription_id", "must not be null");
        }
        if (evt.customer_id() == null) {
            throw invalid(evt, "customer_id", "must not be null");
        }
        if (evt.idempotency_key() == null || evt.idempotency_key().isBlank()) {
            throw invalid(evt, "idempotency_key", "must not be null or blank");
        }
        if (evt.currency() == null || evt.currency().isBlank()) {
            throw invalid(evt, "currency", "must not be null or blank");
        }
        if (evt.amount_cents() <= 0) {
            throw invalid(evt, "amount_cents", "must be greater than zero");
        }

        parseDate(evt, "due_date", evt.due_date());
        LocalDate periodStart = parseDate(evt, "period_start", evt.period_start());
        LocalDate periodEnd = parseDate(evt, "period_end", evt.period_end());
        if (!periodEnd.isAfter(periodStart)) {
            throw invalid(evt, "period_end", "must be after period_start");
        }
    }

    private LocalDate parseDate(RenewalRequested evt, String field, String value) {
        if (value == null) {
            throw invalid(evt, field, "must not be null");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(evt, field, "must be an ISO date");
        }
    }

    private InvalidRenewalMessageException invalid(RenewalRequested evt, String field, String detail) {
        return new InvalidRenewalMessageException(
                "Invalid renewal message field " + field
                        + " (event_id=" + evt.event_id()
                        + ", subscription_id=" + evt.subscription_id() + "): " + detail);
    }

    private UUID upsertInvoice(UUID customerId, LocalDate ps, LocalDate pe,
                               long total, String currency) {
        // Try insert; if unique constraint exists it will do nothing
        jdbc.update("""
                INSERT INTO invoice(id, customer_id, period_start, period_end,
                total_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'posted')
                ON CONFLICT ON CONSTRAINT uniq_invoice_period DO NOTHING
                """, UUID.randomUUID(), customerId, ps, pe, total, currency);
        return jdbc.queryForObject("""
                SELECT id FROM invoice
                WHERE customer_id = ? AND period_start = ? AND period_end = ? AND
                currency = ?
                """, UUID.class, customerId, ps, pe, currency);
    }

    private UUID upsertCharge(UUID subscriptionId, UUID invoiceId, long
            amount, String currency, LocalDate dueDate) {
        jdbc.update("""
                        INSERT INTO charge(id, subscription_id, invoice_id, amount_cents,
                        currency, status, due_date)
                        VALUES (?, ?, ?, ?, ?, 'pending', ?)
                        ON CONFLICT ON CONSTRAINT uniq_charge_period DO NOTHING
                        """, UUID.randomUUID(), subscriptionId, invoiceId, amount, currency,
                dueDate);
        return jdbc.queryForObject("""
                SELECT id FROM charge
                WHERE subscription_id = ? AND due_date = ? AND amount_cents = ? AND
                currency = ?
                """, UUID.class, subscriptionId, dueDate, amount, currency);
    }

    private UUID upsertPayment(String idempotencyKey, UUID chargeId, long
            amount, String currency) {
        // Guard with UNIQUE(idempotency_key)
        jdbc.update("""
                INSERT INTO payment(id, charge_id, amount_cents, currency, channel,
                idempotency_key, status)
                VALUES (?, ?, ?, ?, 'CARD', ?, 'pending')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, UUID.randomUUID(), chargeId, amount, currency, idempotencyKey);
        return jdbc.queryForObject("SELECT id FROM payment WHERE idempotency_key = ? ", UUID.class, idempotencyKey);
    }

    private void markPaymentSucceeded(UUID paymentId) {
        jdbc.update("UPDATE payment SET status = 'succeeded', completed_at = now()WHERE id = ? ", paymentId);
    }

    private void markPaymentFailed(UUID paymentId) {
        jdbc.update("UPDATE payment SET status = 'failed', completed_at = now() WHERE id = ?", paymentId);
    }

    private void finalizeBilling(UUID invoiceId, UUID chargeId, UUID
            subscriptionId, LocalDate newRenewalDate) {
        jdbc.update("UPDATE charge SET status = 'settled' WHERE id = ?",
                chargeId);
        jdbc.update("UPDATE invoice SET status = 'paid' WHERE id = ?",
                invoiceId);
        // advance renewed_at to period end at 09:00 local (stored as TIMESTAMP)
        LocalDateTime ldt = newRenewalDate.atTime(9, 0);
        jdbc.update("UPDATE subscription SET renewed_at = ? WHERE id = ?",
                Timestamp.valueOf(ldt), subscriptionId);
    }
}
