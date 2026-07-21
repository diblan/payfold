package com.blanchaert.billing.producer.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private static final String SCAN_CURSOR_KEY = "scanStep.cursor";
    private static final String SCAN_WINDOW_KEY = "scanStep.window";
    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    @Bean
    public ThreadPoolTaskExecutor renewalJobTaskExecutor() {
        // Single thread: concurrent force-triggers queue and run serially instead of
        // stacking job threads; SKIP LOCKED already makes overlap row-safe, so this
        // is hygiene, not correctness. NOT named "taskExecutor": @EnableBatchProcessing
        // wires a bean of that name into the DEFAULT launcher, which would silently
        // make the cron path async as well.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("renewal-job-");
        return executor;
    }

    @Bean
    public JobLauncher asyncJobLauncher(JobRepository repo,
                                        @Qualifier("renewalJobTaskExecutor") ThreadPoolTaskExecutor executor) throws Exception {
        // Endpoint-only launcher. The scheduler must keep Batch's default synchronous
        // "jobLauncher": it releases the Postgres advisory lock when run() returns, so
        // an async launch there would release the lock while the job still runs and
        // destroy R7's cross-instance serialization.
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repo);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }

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
                         @Value("${app.timezone:Europe/Brussels}") String tz,
                         @Value("${app.scanPageSize:10000}") int scanPageSize) {
        Counter insertedCounter = Counter.builder("outbox.inserted")
                .description("Outbox rows inserted by scanStep")
                .register(meters);
        return new StepBuilder("scanStep", repo)
                .tasklet((contribution, chunkContext) -> {
                    // Keyset-paginated scan: each iteration handles one PK-ordered page of active
                    // subscriptions in its own transaction and re-runs via RepeatStatus.CONTINUABLE.
                    // The scan deliberately reads every active subscription (10M at target scale) to
                    // find the due ~330k: the due predicate (renewed_at + plan interval, localized to
                    // a configurable timezone) is cross-table and non-IMMUTABLE, so Postgres cannot
                    // carry it in an expression index, and a PK-ordered pass is sequential-friendly
                    // I/O with no extra write amplification (D10). The due-window filter sits INSIDE
                    // the page, so the page query reports its row count and last id no matter how
                    // many rows were due — all-not-due pages still advance the cursor, and the loop
                    // ends on a short page, never on inserted == 0. The cursor and the due window
                    // (fixed on the first page so a scan crossing midnight keeps one consistent
                    // window) live in the step ExecutionContext, which Spring Batch persists in the
                    // same transaction as the page's inserts: a crash resumes from the last
                    // committed page, and ON CONFLICT DO NOTHING absorbs the one re-scanned page.
                    ExecutionContext stepCtx = chunkContext.getStepContext().getStepExecution().getExecutionContext();
                    ZoneId zone = ZoneId.of(tz);
                    String windowDate = stepCtx.getString(SCAN_WINDOW_KEY, null);
                    LocalDate today = windowDate == null ? LocalDate.now(zone) : LocalDate.parse(windowDate);
                    if (windowDate == null) {
                        stepCtx.putString(SCAN_WINDOW_KEY, today.toString());
                    }
                    LocalDateTime start = today.atStartOfDay();
                    LocalDateTime end = today.plusDays(1).atStartOfDay();
                    UUID cursor = UUID.fromString(stepCtx.getString(SCAN_CURSOR_KEY, NIL_UUID));

                    String sql = """
                            WITH page AS (
                                SELECT s.id, s.customer_id, s.plan_id, s.renewed_at
                                FROM subscription s
                                WHERE s.status = 'active'
                                  AND s.renewed_at IS NOT NULL
                                  AND s.id > ?
                                ORDER BY s.id
                                LIMIT ?
                            ), due AS (
                                SELECT p.id AS subscription_id, p.customer_id, p.plan_id,
                                    pl.interval, pl.price_cents, pl.currency,
                                    (CASE WHEN pl.interval = 'year'
                                        THEN (p.renewed_at + INTERVAL '1 year')
                                        ELSE (p.renewed_at + INTERVAL '1 month')
                                        END) AS due_ts
                                FROM page p
                                JOIN plan pl ON pl.id = p.plan_id
                            ), win AS (
                                SELECT *, due_ts AT TIME ZONE ? AS due_local
                                FROM due
                            ), events AS (
                                SELECT *, gen_random_uuid() AS event_id,
                                    (due_local)::date AS due_date
                                FROM win
                                WHERE due_local >= ? AND due_local < ?
                            ), ins AS (
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
                                ON CONFLICT (subscription_id, due_date) DO NOTHING
                                RETURNING 1
                            )
                            SELECT count(*) AS page_rows,
                                   (SELECT id FROM page ORDER BY id DESC LIMIT 1) AS last_id,
                                   (SELECT count(*) FROM ins) AS inserted
                            FROM page
                            """;

                    record ScanPage(long pageRows, UUID lastId, long inserted) {
                    }
                    ScanPage page = jdbc.query(con -> {
                        var ps = con.prepareStatement(sql);
                        ps.setObject(1, cursor);
                        ps.setInt(2, scanPageSize);
                        ps.setString(3, zone.getId());
                        ps.setObject(4, start);
                        ps.setObject(5, end);
                        return ps;
                    }, rs -> {
                        rs.next();
                        return new ScanPage(
                                rs.getLong("page_rows"),
                                (UUID) rs.getObject("last_id"),
                                rs.getLong("inserted")
                        );
                    });

                    insertedCounter.increment(page.inserted());
                    log.info("Scan page: {} active subscriptions examined, {} outbox rows inserted",
                            page.pageRows(), page.inserted());
                    if (page.pageRows() < scanPageSize) {
                        return RepeatStatus.FINISHED;
                    }
                    stepCtx.putString(SCAN_CURSOR_KEY, page.lastId().toString());
                    return RepeatStatus.CONTINUABLE;
                }, tx).build();
    }

    @Bean
    public Step publishStep(JobRepository repo,
                            PlatformTransactionManager tx,
                            JdbcTemplate jdbc,
                            OutboxPublisher publisher,
                            MeterRegistry meters,
                            @Value("${app.publishPageSize:10000}") int publishPageSize,
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
