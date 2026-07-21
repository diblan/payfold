package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import com.blanchaert.billing.producer.web.RenewalJobEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.confirmTimeoutMs=30000"})
@Testcontainers
class AsyncTriggerEndpointTest {
    private static final UUID OUTBOX_ROW_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000000e");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Autowired
    private RenewalJobEndpoint endpoint;

    @Autowired
    private JobExplorer explorer;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxPublisher publisher;

    @Test
    void triggerReturnsExecutionIdWhileJobStillRunsAndStatusTracksItToCompletion() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000300");
        UUID subscriptionId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)",
                customerId, "async-trigger-probe@example.test");
        jdbc.update(
                "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) "
                        + "VALUES (?, ?, ?, 'active', NULL)",
                subscriptionId, customerId, planId
        );
        jdbc.update(
                "INSERT INTO renewal_outbox (id, subscription_id, due_date, payload) VALUES "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"async-trigger\"}'::jsonb)",
                OUTBOX_ROW_ID, subscriptionId
        );

        var publishCalled = new CountDownLatch(1);
        var confirmGate = new CompletableFuture<Boolean>();
        when(publisher.publish(anyString(), anyString())).thenAnswer(invocation -> {
            publishCalled.countDown();
            return confirmGate;
        });

        Map<String, Object> response = endpoint.trigger(true);

        // trigger() returned while the confirm future still gates the publish
        // step — the job cannot have finished. That, not timing, proves async.
        assertThat(response).containsKeys("job", "instanceId", "status", "parameters", "executionId");
        Long executionId = (Long) response.get("executionId");
        assertThat(executionId).isNotNull();
        assertThat((String) response.get("status")).isIn("STARTING", "STARTED");

        assertThat(publishCalled.await(30, TimeUnit.SECONDS)).isTrue();

        // Mid-publish, the read operation reports a live non-terminal execution.
        Map<String, Object> running = endpoint.status(executionId);
        assertThat(running).isNotNull();
        assertThat((String) running.get("status")).isIn("STARTING", "STARTED");
        assertThat(running.get("endTime")).isNull();

        confirmGate.complete(true);

        // Poll-with-timeout for the eventually-consistent read; never a single read.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JobExecution execution = explorer.getJobExecution(executionId);
            assertThat(execution).isNotNull();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        });

        Map<String, Object> done = endpoint.status(executionId);
        assertThat(done.get("executionId")).isEqualTo(executionId);
        assertThat(done.get("job")).isEqualTo("renewalJob");
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("exitStatus")).isEqualTo("COMPLETED");
        assertThat(done.get("endTime")).isNotNull();

        // The gated row was actually published once the confirm resolved.
        assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM renewal_outbox WHERE id = ?",
                Boolean.class, OUTBOX_ROW_ID)).isTrue();

        // Unknown id → null, which the actuator layer renders as HTTP 404.
        assertThat(endpoint.status(Long.MAX_VALUE)).isNull();
    }
}
