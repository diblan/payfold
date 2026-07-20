package com.blanchaert.billing.producer.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration
@EnableBatchProcessing
public class RenewalJobConfig {
    private static final Logger log = LoggerFactory.getLogger(RenewalJobConfig.class);

    @Bean
    public Job renewalJob(
            JobRepository repo,
            @Qualifier("scanStep") Step scanStep,
            @Qualifier("publishStep") Step publishStep
    ) {
        return new JobBuilder("renewalJob", repo)
                .start(scanStep)
                .next(publishStep)
                .build();
    }

    @Bean
    public Step scanStep(JobRepository repo,
                         PlatformTransactionManager tx,
                         JdbcTemplate jdbc,
                         MeterRegistry meters,
                         @Value("${app.timezone:Europe/Brussels}") String tz) {
        Counter insertedCounter = Counter.builder("outbox.inserted")
                .description("Outbox rows inserted by scanStep")
                .register(meters);
        return new StepBuilder("scanStep", repo)
                .tasklet((contribution, chunkContext) -> {
                    ZoneId zone = ZoneId.of(tz);
                    LocalDate today = LocalDate.now(zone);
                    LocalDateTime start = today.atStartOfDay();
                    LocalDateTime end = today.plusDays(1).atStartOfDay();
                    // Insert into outbox in the same tx; guard against duplicates via (subscription_id, due_date)
                    String sql = """
                            WITH due AS (
                                SELECT s.id AS subscription_id, s.customer_id, s.plan_id,
                                    p.interval, p.price_cents, p.currency,
                                (CASE WHEN p.interval = 'year'
                                    THEN (s.renewed_at + INTERVAL '1 year')
                                    ELSE (s.renewed_at + INTERVAL '1 month')
                                    END) AS due_ts
                                FROM subscription s
                                JOIN plan p ON p.id = s.plan_id
                                WHERE s.status = 'active'
                                AND s.renewed_at IS NOT NULL
                            ), win AS (
                                SELECT *, due_ts AT TIME ZONE ? AS due_local
                                FROM due
                            ), events AS (
                                SELECT *, gen_random_uuid() AS event_id,
                                    (due_local)::date AS due_date
                                FROM win
                                WHERE due_local >= ? AND due_local < ?
                            )
                            INSERT INTO renewal_outbox (id, subscription_id, due_date, payload)
                            SELECT event_id,
                                subscription_id,
                                due_date,
                                jsonb_build_object(
                                    'schema_version', 1,
                                    'event_id', event_id,
                                    'subscription_id', subscription_id,
                                    'customer_id', customer_id,
                                    'plan_id', plan_id,
                                    'interval', interval,
                                    'amount_cents', price_cents,
                                    'currency', currency,
                                    'idempotency_key', 'sub-' || subscription_id || '|' || to_char(due_date, 'YYYY-MM-DD'),
                                    'due_date', to_char(due_date, 'YYYY-MM-DD'),
                                    'period_start', to_char(due_date, 'YYYY-MM-DD'),
                                    'period_end', to_char(
                                        (CASE
                                            WHEN interval = 'month' THEN due_date + INTERVAL '1 month'
                                            WHEN interval = 'year' THEN due_date + INTERVAL '1 year'
                                        END)::date,
                                        'YYYY-MM-DD'
                                    ),
                                    'occurred_at', to_char(
                                        now() AT TIME ZONE 'UTC',
                                        'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
                                    )
                                ) AS payload
                            FROM events
                            ON CONFLICT (subscription_id, due_date) DO NOTHING;
                            """;
                    int inserted = jdbc.update(con -> {
                        var ps = con.prepareStatement(sql);
                        ps.setString(1, zone.getId());
                        ps.setObject(2, start);
                        ps.setObject(3, end);
                        return ps;
                    });
                    insertedCounter.increment(inserted);
                    log.info("Outbox rows inserted: {}", inserted);
                    return RepeatStatus.FINISHED;
                }, tx).build();
    }

    @Bean
    public Step publishStep(JobRepository repo,
                            PlatformTransactionManager tx,
                            JdbcTemplate jdbc,
                            OutboxPublisher publisher,
                            MeterRegistry meters,
                            @Value("${app.publishPageSize:1000}") int publishPageSize,
                            @Value("${app.confirmTimeoutMs:10000}") long confirmTimeoutMs) {
        Counter publishedCounter = Counter.builder("outbox.published")
                .description("Outbox rows confirmed published")
                .register(meters);
        return new StepBuilder("publishStep", repo)
                .tasklet((contribution, chunkContext) -> {
                    // Claim one page of unpublished rows; SKIP LOCKED keeps concurrent publishers disjoint
                    record OutboxRow(UUID id, String payload) {
                    }
                    var rows = jdbc.query(
                            "SELECT id, payload " +
                                    "FROM renewal_outbox " +
                                    "WHERE published_at IS NULL " +
                                    "ORDER BY id " +
                                    "LIMIT ? " +
                                    "FOR UPDATE SKIP LOCKED",
                            ps -> ps.setInt(1, publishPageSize),
                            (rs, i) -> new OutboxRow((UUID) rs.getObject("id"), rs.getString("payload"))
                    );

                    if (rows.isEmpty()) {
                        log.info("No publishable outbox rows visible (drained, or remainder claimed by a concurrent publisher).");
                        return RepeatStatus.FINISHED; // stop the step
                    }

                    var futures = new LinkedHashMap<UUID, CompletableFuture<Boolean>>(rows.size());
                    for (var row : rows) {
                        futures.put(row.id(), publisher.publish(row.id().toString(), row.payload()));
                    }

                    long deadline = System.nanoTime() + confirmTimeoutMs * 1_000_000L;
                    var confirmedIds = new ArrayList<UUID>(rows.size());
                    for (var entry : futures.entrySet()) {
                        long remaining = Math.max(deadline - System.nanoTime(), 0L);
                        try {
                            if (entry.getValue().get(remaining, TimeUnit.NANOSECONDS)) {
                                confirmedIds.add(entry.getKey());
                            }
                        } catch (TimeoutException | ExecutionException | CancellationException ignored) {
                            // Leave the row unpublished so the next page or job run re-picks it.
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted awaiting publisher confirms", e);
                        }
                    }

                    if (confirmedIds.isEmpty()) {
                        throw new IllegalStateException(
                                "0/" + rows.size() + " rows confirmed within " + confirmTimeoutMs
                                        + " ms"
                        );
                    }

                    jdbc.batchUpdate(
                            "UPDATE renewal_outbox SET published_at = now() WHERE id = ?",
                            confirmedIds,
                            confirmedIds.size(),
                            (ps, id) -> ps.setObject(1, id)
                    );
                    publishedCounter.increment(confirmedIds.size());

                    int unconfirmedCount = rows.size() - confirmedIds.size();
                    if (unconfirmedCount > 0) {
                        log.warn("{} of {} unconfirmed, rows stay unpublished and will be re-picked.",
                                unconfirmedCount, rows.size());
                    }

                    log.info("Published page count: {}", confirmedIds.size());
                    return RepeatStatus.CONTINUABLE; // ask Batch to run this tasklet again (new tx), next page
                }, tx).build();
    }
}
