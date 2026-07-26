package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.bank.BankClient;
import com.blanchaert.billing.consumer.config.BankProperties;
import com.blanchaert.billing.consumer.config.BankRegistry;
import com.blanchaert.billing.consumer.model.RenewalRequested;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate jdbc;
    private final BankClient bank;
    private final BankRegistry bankRegistry;
    private final Map<String, Counter> processedCounters;

    public BillingService(JdbcTemplate jdbc, BankClient bank,
                          BankRegistry bankRegistry, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.bank = bank;
        this.bankRegistry = bankRegistry;
        Map<String, Counter> counters = new HashMap<>();
        for (String method : new String[]{"card", "sdd", "unknown"}) {
            for (String outcome : new String[]{"succeeded", "failed", "invalid", "submitted"}) {
                counters.put(counterKey(outcome, method), processedCounter(meters, outcome, method));
            }
        }
        this.processedCounters = Map.copyOf(counters);
    }

    public void process(RenewalRequested evt) {
        try {
            validate(evt);
        } catch (InvalidRenewalMessageException e) {
            incrementProcessed("invalid", "unknown");
            throw e;
        }

        CustomerBilling customerBilling = jdbc.query("""
                        SELECT payment_method, debtor_iban, mandate_reference, country,
                               card_token
                        FROM customer
                        WHERE id = ?
                        """,
                rs -> rs.next()
                        ? new CustomerBilling(
                                rs.getString("payment_method"),
                                rs.getString("debtor_iban"),
                                rs.getString("mandate_reference"),
                                rs.getString("country"),
                                rs.getString("card_token"))
                        : null,
                evt.customer_id());
        if (customerBilling == null) {
            incrementProcessed("invalid", "unknown");
            throw invalid(evt, "customer_id", "customer not found");
        }

        BankProperties.BankEntry bankEntry = null;
        if ("sdd".equals(customerBilling.paymentMethod())) {
            bankEntry = bankRegistry.byCountry(customerBilling.country());
            if (bankEntry == null) {
                incrementProcessed("invalid", customerBilling.paymentMethod());
                throw invalid(evt, "customer_id",
                        "no bank routes country " + customerBilling.country());
            }
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
        String channel = "sdd".equals(customerBilling.paymentMethod()) ? "SEPA_DD" : "CARD";
        UUID paymentId = upsertPayment(idem, chargeId, evt.amount_cents(), evt.currency(), channel);
        if ("sdd".equals(customerBilling.paymentMethod())) {
            String sddStatus = jdbc.queryForObject(
                    "SELECT status FROM payment WHERE id = ?", String.class, paymentId);
            if ("pending".equals(sddStatus)) {
                // Submit BEFORE flipping status: a crash after the bank accepted is
                // healed by redelivery re-submitting the same collection_id, which
                // the bank deduplicates (200 duplicate). collection_id IS the
                // idempotency key: one renewal, one collection, forever.
                bank.submitCollection(bankEntry.id(), idem, evt.amount_cents(), evt.currency(),
                        customerBilling.debtorIban(), customerBilling.mandateReference(), evt.due_date());
                jdbc.update("""
                                UPDATE payment
                                SET status = 'submitted', bank_id = ?, collection_id = ?
                                WHERE id = ? AND status = 'pending'
                                """,
                        bankEntry.id(), idem, paymentId);
            }
            // Parked or already handled: settlement finalizes via the R23c inbox
            // spine, never here. Nothing is finalized on the submission path.
            incrementProcessed("submitted", "sdd");
            return;
        }

        // card (D17): authorization is the one genuinely synchronous hop in the
        // card network, so a decline is terminal immediately; fulfillment-grade
        // confirmation arrives later as a settlement event on the same spine.
        BankProperties.BankEntry cardEntry = bankRegistry.cardEntry();
        String cardStatus = jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?", String.class, paymentId);
        if ("pending".equals(cardStatus)) {
            BankClient.CardVerdict verdict = bank.submitCardAuthorization(
                    cardEntry.id(), idem, evt.amount_cents(), evt.currency(),
                    customerBilling.cardToken(), evt.due_date());
            if (!verdict.authorized()) {
                jdbc.update("""
                                UPDATE payment
                                SET status = 'failed', failure_reason = ?,
                                    completed_at = now()
                                WHERE id = ? AND status = 'pending'
                                """,
                        verdict.reason(), paymentId);
                log.info("Card authorization declined for {}: {}", idem, verdict.reason());
                incrementProcessed("failed", "card");
                return;
            }
            jdbc.update("""
                            UPDATE payment
                            SET status = 'submitted', bank_id = ?, collection_id = ?
                            WHERE id = ? AND status = 'pending'
                            """,
                    cardEntry.id(), idem, paymentId);
            incrementProcessed("submitted", "card");
            return;
        }
        if ("failed".equals(cardStatus)) {
            incrementProcessed("failed", "card");
            return;
        }
        if ("submitted".equals(cardStatus)) {
            incrementProcessed("submitted", "card");
            return;
        }
        // succeeded / charged_back: settlement already finalized this renewal.
        incrementProcessed("succeeded", "card");
    }

    private Counter processedCounter(MeterRegistry meters, String outcome, String method) {
        return Counter.builder("renewals.processed")
                .description("Renewal messages by processing outcome")
                .tag("outcome", outcome)
                .tag("method", method)
                .register(meters);
    }

    private void incrementProcessed(String outcome, String method) {
        String normalizedMethod = "card".equals(method) || "sdd".equals(method)
                ? method : "unknown";
        processedCounters.get(counterKey(outcome, normalizedMethod)).increment();
    }

    private String counterKey(String outcome, String method) {
        return outcome + "|" + method;
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
            amount, String currency, String channel) {
        // Guard with UNIQUE(idempotency_key)
        jdbc.update("""
                INSERT INTO payment(id, charge_id, amount_cents, currency, channel,
                idempotency_key, status)
                VALUES (?, ?, ?, ?, ?, ?, 'pending')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, UUID.randomUUID(), chargeId, amount, currency, channel, idempotencyKey);
        return jdbc.queryForObject("SELECT id FROM payment WHERE idempotency_key = ? ", UUID.class, idempotencyKey);
    }

    record CustomerBilling(
            String paymentMethod, String debtorIban, String mandateReference,
            String country, String cardToken) {
    }
}
