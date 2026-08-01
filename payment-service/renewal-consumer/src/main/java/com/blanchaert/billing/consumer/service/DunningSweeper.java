package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.bank.BankClient;
import com.blanchaert.billing.consumer.config.BankRegistry;
import com.blanchaert.billing.consumer.config.DunningProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@Transactional
public class DunningSweeper {
    private static final Logger log = LoggerFactory.getLogger(DunningSweeper.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final BankClient bank;
    private final BankRegistry bankRegistry;
    private final DunningProperties properties;
    private final TransactionTemplate retryTransaction;
    private final Map<String, Counter> retries;

    public DunningSweeper(
            JdbcTemplate jdbc, BankClient bank, BankRegistry bankRegistry,
            DunningProperties properties, PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbc.getDataSource()));
        this.bank = bank;
        this.bankRegistry = bankRegistry;
        this.properties = properties;
        this.retryTransaction = new TransactionTemplate(transactionManager);
        this.retryTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retries = Map.of(
                "submitted", retryCounter(meters, "submitted"),
                "declined", retryCounter(meters, "declined"),
                "duplicate", retryCounter(meters, "duplicate"));
    }

    @Scheduled(fixedDelayString = "${dunning.sweep-interval-ms:60000}")
    public void sweep() {
        sweepOnce();
    }

    @Transactional
    public void sweepOnce() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('dunning-sweeper'))",
                Boolean.class);
        if (!Boolean.TRUE.equals(acquired) || properties.retriableReasons().isEmpty()) {
            return;
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("reasons", properties.retriableReasons())
                .addValue("delaySeconds", properties.retryDelaySeconds());
        // The latest-attempt plus failed-status predicate is the R28 boundary:
        // submitted rows stay invisible at any age. Filtering reasons in SQL
        // also prevents hard-fail rows from starving the bounded page.
        List<PaymentRow> rows = namedJdbc.query("""
                        SELECT s.id AS subscription_id, ch.id AS charge_id, p.amount_cents,
                               p.currency, p.channel, p.attempt, p.idempotency_key, p.bank_id,
                               ch.due_date, c.debtor_iban, c.mandate_reference, c.card_token
                        FROM subscription s
                        JOIN charge ch ON ch.subscription_id = s.id
                        JOIN payment p ON p.charge_id = ch.id
                        JOIN customer c ON c.id = s.customer_id
                        WHERE s.status = 'past_due'
                          AND p.status = 'failed'
                          AND p.failure_reason IN (:reasons)
                          AND p.completed_at < now() - (:delaySeconds * interval '1 second')
                          AND p.attempt = (SELECT max(p2.attempt) FROM payment p2
                                           WHERE p2.charge_id = ch.id)
                        ORDER BY p.completed_at
                        LIMIT 200
                        """,
                parameters,
                (rs, rowNum) -> new PaymentRow(
                        rs.getObject("subscription_id", UUID.class),
                        rs.getObject("charge_id", UUID.class),
                        rs.getLong("amount_cents"),
                        rs.getString("currency"),
                        rs.getString("channel"),
                        rs.getInt("attempt"),
                        rs.getString("idempotency_key"),
                        rs.getString("bank_id"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getString("debtor_iban"),
                        rs.getString("mandate_reference"),
                        rs.getString("card_token")));

        for (PaymentRow row : rows) {
            try {
                retryTransaction.executeWithoutResult(status -> retryOne(row));
            } catch (Exception exception) {
                // A failed submit must not leave a pending orphan that blinds
                // this picker; rollback discards the insert (D20).
                log.warn("Dunning retry failed for subscription {}",
                        row.subscriptionId(), exception);
            }
        }
    }

    private void retryOne(PaymentRow row) {
        int nextAttempt = row.attempt() + 1;
        String base = row.idempotencyKey().replaceFirst("\\|a\\d+$", "");
        String collectionId = base + "|a" + nextAttempt;
        int inserted = jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, attempt
                ) VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
                ON CONFLICT ON CONSTRAINT uniq_payment_charge_attempt DO NOTHING
                """, UUID.randomUUID(), row.chargeId(), row.amountCents(),
                row.currency(), row.channel(), collectionId, nextAttempt);
        if (inserted == 0) {
            retries.get("duplicate").increment();
            return;
        }

        String bankId = row.bankId();
        if ("CARD".equals(row.channel())) {
            // Synchronous declines have no settlement attribution, so the
            // configured card entry remains the authoritative retry route.
            if (bankId == null) {
                bankId = bankRegistry.cardEntry().id();
            }
            BankClient.CardVerdict verdict = bank.submitCardAuthorization(
                    bankId, collectionId, row.amountCents(), row.currency(),
                    row.cardToken(), row.dueDate().toString());
            if (!verdict.authorized()) {
                jdbc.update("""
                        UPDATE payment
                        SET status = 'failed', failure_reason = ?, completed_at = now()
                        WHERE idempotency_key = ? AND status = 'pending'
                        """, verdict.reason(), collectionId);
                retries.get("declined").increment();
                log.info("Card re-collection {} declined: {}",
                        collectionId, verdict.reason());
                return;
            }
        } else {
            bank.submitCollection(
                    bankId, collectionId, row.amountCents(), row.currency(),
                    row.debtorIban(), row.mandateReference(), row.dueDate().toString());
        }

        jdbc.update("""
                UPDATE payment
                SET status = 'submitted', bank_id = ?, collection_id = ?
                WHERE idempotency_key = ? AND status = 'pending'
                """, bankId, collectionId, collectionId);
        retries.get("submitted").increment();
        log.info("Submitted dunning re-collection {} (attempt={})",
                collectionId, nextAttempt);
    }

    private Counter retryCounter(MeterRegistry meters, String outcome) {
        return Counter.builder("dunning.retries")
                .description("Dunning re-collection attempt outcomes")
                .tag("outcome", outcome)
                .register(meters);
    }

    private record PaymentRow(
            UUID subscriptionId,
            UUID chargeId,
            long amountCents,
            String currency,
            String channel,
            int attempt,
            String idempotencyKey,
            String bankId,
            LocalDate dueDate,
            String debtorIban,
            String mandateReference,
            String cardToken) {
    }
}
