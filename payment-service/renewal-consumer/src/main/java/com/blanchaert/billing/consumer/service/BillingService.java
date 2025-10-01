package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;

@Service
public class BillingService {
    private final JdbcTemplate jdbc;

    public BillingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void process(RenewalRequested evt) {
        // 1) Compute period
        LocalDate ps = evt.period_start() != null ? LocalDate.parse(evt.period_start()) : LocalDate.now();
        LocalDate pe = evt.period_end() != null ? LocalDate.parse(evt.period_end()) : ("year".equalsIgnoreCase(evt.interval()) ? ps.plusYears(1) : ps.plusMonths(1));
        String idem = (evt.idempotency_key() != null && !evt.idempotency_key().isBlank()) ? evt.idempotency_key() : ("sub-" + evt.subscription_id() + "|" + ps);
        // 2) Upsert invoice
        UUID invoiceId = upsertInvoice(evt.customer_id(), ps, pe, evt.amount_cents(), evt.currency());
        // 3) Upsert charge linked to subscription + invoice + due_date
        UUID chargeId = upsertCharge(evt.subscription_id(), invoiceId, evt.amount_cents(), evt.currency(), pe);
        // 4) Create payment row (pending) guarded by idempotency unique key
        UUID paymentId = upsertPayment(idem, chargeId, evt.amount_cents(), evt.currency());
        // 5) Simulate call to payment provider (replace with real PSP). Here we mark as succeeded.
        markPaymentSucceeded(paymentId);
        // 6) Mark invoice paid, charge settled; advance subscription. Use Europe / Brussels 09:00 local
        finalizeBilling(invoiceId, chargeId, evt.subscription_id(), pe);
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