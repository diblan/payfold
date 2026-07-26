package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The publish page must never hold more than {@code app.publishInFlightLimit}
 * unconfirmed sends in flight: each unconfirmed send parks one broker channel
 * (see PublisherChannelParkingTest), so the window is what bounds the page's
 * channel budget under a slow-confirming broker. A window stalled to the page
 * deadline stops sending and keeps the zero-progress failure loud; a window
 * that drains as confirms arrive publishes the whole page without ever
 * exceeding the limit.
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "app.publishInFlightLimit=3",
        "app.confirmTimeoutMs=2000"})
@Testcontainers
class PublishInFlightWindowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    // The SYNC launcher: these assertions depend on run() returning only
    // after the job finished.
    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    private Job renewalJob;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxPublisher publisher;

    @Test
    void stalledWindowStopsSendingAtTheLimitAndZeroProgressStaysLoud() throws Exception {
        seedOutboxRows("window-stall@example.test", 10);

        when(publisher.publish(anyString(), anyString()))
                .thenAnswer(invocation -> new CompletableFuture<Boolean>());

        var execution = jobLauncher.run(renewalJob, uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(publisher, times(3)).publish(anyString(), anyString());
        assertThat(publishedCount()).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class))
                .isEqualTo(10L);
    }

    @Test
    void lateConfirmsFlowThroughTheWindowWithoutExceedingTheLimit() throws Exception {
        seedOutboxRows("window-flow@example.test", 9);

        // In-flight bookkeeping shared between the publish stub (job thread) and
        // the completer thread; completionsIssued is incremented BEFORE the
        // future completes, so a send that observed a freed window slot always
        // sees the completion that freed it — maxInFlight is deterministic.
        Object lock = new Object();
        int[] sends = {0};
        int[] completionsIssued = {0};
        int[] maxInFlight = {0};
        Deque<CompletableFuture<Boolean>> pending = new ArrayDeque<>();
        Semaphore batchReady = new Semaphore(0);
        when(publisher.publish(anyString(), anyString())).thenAnswer(invocation -> {
            var future = new CompletableFuture<Boolean>();
            synchronized (lock) {
                sends[0]++;
                maxInFlight[0] = Math.max(maxInFlight[0], sends[0] - completionsIssued[0]);
                pending.addLast(future);
                if (sends[0] % 3 == 0) {
                    batchReady.release();
                }
            }
            return future;
        });

        ExecutorService completerExecutor = Executors.newSingleThreadExecutor();
        Future<?> completerDone = completerExecutor.submit(() -> {
            try {
                for (int batch = 0; batch < 3; batch++) {
                    if (!batchReady.tryAcquire(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("window never filled for batch " + batch);
                    }
                    List<CompletableFuture<Boolean>> toComplete = new ArrayList<>();
                    synchronized (lock) {
                        for (int i = 0; i < 3; i++) {
                            completionsIssued[0]++;
                            toComplete.add(pending.removeFirst());
                        }
                    }
                    toComplete.forEach(future -> future.complete(true));
                }
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        try {
            var execution = jobLauncher.run(renewalJob, uniqueParameters());
            completerDone.get(30, TimeUnit.SECONDS);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }
        finally {
            completerExecutor.shutdownNow();
        }

        synchronized (lock) {
            assertThat(sends[0]).isEqualTo(9);
            assertThat(maxInFlight[0]).isEqualTo(3);
        }
        assertThat(publishedCount()).isEqualTo(9L);
    }

    /**
     * Empties the outbox (methods share one container; publishStep claims every
     * unpublished row, so leftovers from a sibling method would widen the page)
     * and seeds {@code count} outbox rows whose subscriptions are invisible to
     * scanStep ({@code renewed_at} NULL).
     */
    private void seedOutboxRows(String email, int count) {
        jdbc.update("DELETE FROM renewal_outbox");
        UUID customerId = UUID.randomUUID();
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", customerId, email);
        for (int i = 0; i < count; i++) {
            UUID subscriptionId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) VALUES (?, ?, ?, 'active', NULL)",
                    subscriptionId, customerId, planId);
            jdbc.update(
                    "INSERT INTO renewal_outbox (id, subscription_id, due_date, payload) VALUES (?, ?, DATE '2026-01-01', ?::jsonb)",
                    UUID.randomUUID(), subscriptionId, "{\"probe\": \"window-" + i + "\"}");
        }
    }

    private long publishedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL", Long.class);
    }

    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addString("scheduleDate", "2026-01-01")
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters();
    }
}
