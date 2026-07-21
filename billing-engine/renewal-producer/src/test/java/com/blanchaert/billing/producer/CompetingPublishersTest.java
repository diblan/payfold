package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "app.publishPageSize=2",
        "app.confirmTimeoutMs=30000"
})
@Testcontainers
class CompetingPublishersTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    // The SYNC launcher: this test's assertions depend on run() returning only
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
    void concurrentPublishersClaimDisjointPagesWithoutSkippingRows() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000200");
        UUID subscriptionA = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID subscriptionB = UUID.fromString("00000000-0000-0000-0000-000000000202");
        UUID subscriptionC = UUID.fromString("00000000-0000-0000-0000-000000000203");
        UUID subscriptionD = UUID.fromString("00000000-0000-0000-0000-000000000204");
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", customerId, "competing-probe@example.test");
        jdbc.batchUpdate(
                "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) VALUES (?, ?, ?, 'active', NULL)",
                java.util.List.of(subscriptionA, subscriptionB, subscriptionC, subscriptionD),
                4,
                (statement, subscriptionId) -> {
                    statement.setObject(1, subscriptionId);
                    statement.setObject(2, customerId);
                    statement.setObject(3, planId);
                }
        );
        jdbc.update(
                "INSERT INTO renewal_outbox (id, subscription_id, due_date, payload) VALUES "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"competing-a\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"competing-b\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"competing-c\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"competing-d\"}'::jsonb)",
                A, subscriptionA, B, subscriptionB, C, subscriptionC, D, subscriptionD
        );

        var futures = new ConcurrentHashMap<String, CompletableFuture<Boolean>>();
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        var firstPageInFlight = new CountDownLatch(2);
        var bothPagesInFlight = new CountDownLatch(4);
        when(publisher.publish(anyString(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            calls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            var future = futures.computeIfAbsent(id, key -> new CompletableFuture<>());
            firstPageInFlight.countDown();
            bothPagesInFlight.countDown();
            return future;
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var job1 = executor.submit(() -> jobLauncher.run(
                    renewalJob,
                    new JobParametersBuilder()
                            .addString("scheduleDate", "2026-01-01")
                            .addLong("run.id", 1L)
                            .toJobParameters()
            ));

            assertThat(firstPageInFlight.await(30, TimeUnit.SECONDS)).isTrue();
            Set<String> firstPage = Set.copyOf(futures.keySet());
            assertThat(firstPage).containsExactlyInAnyOrder(A.toString(), B.toString());

            var job2 = executor.submit(() -> jobLauncher.run(
                    renewalJob,
                    new JobParametersBuilder()
                            .addString("scheduleDate", "2026-01-01")
                            .addLong("run.id", 2L)
                            .toJobParameters()
            ));

            assertThat(bothPagesInFlight.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(futures.keySet()).containsExactlyInAnyOrder(
                    A.toString(), B.toString(), C.toString(), D.toString()
            );
            assertThat(job1).isNotDone();
            assertThat(job2).isNotDone();

            futures.values().forEach(future -> future.complete(true));

            assertThat(job1.get(60, TimeUnit.SECONDS).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(job2.get(60, TimeUnit.SECONDS).getStatus()).isEqualTo(BatchStatus.COMPLETED);

            assertThat(calls).hasSize(4);
            assertThat(calls.values()).allSatisfy(counter -> assertThat(counter.get()).isEqualTo(1));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM renewal_outbox WHERE published_at IS NULL",
                    Long.class
            )).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(4L);
        } finally {
            executor.shutdownNow();
        }
    }
}
