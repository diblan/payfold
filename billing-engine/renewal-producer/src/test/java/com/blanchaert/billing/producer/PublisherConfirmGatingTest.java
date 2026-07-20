package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.confirmTimeoutMs=250"})
@Testcontainers
class PublisherConfirmGatingTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job renewalJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @MockitoBean
    private OutboxPublisher publisher;

    @Test
    void marksOnlyConfirmedRowsAndRepicksUnconfirmedRows() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000100");
        UUID subscriptionA = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID subscriptionB = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID subscriptionC = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", customerId, "confirm-probe@example.test");
        jdbc.batchUpdate(
                "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) VALUES (?, ?, ?, 'active', NULL)",
                java.util.List.of(subscriptionA, subscriptionB, subscriptionC),
                3,
                (statement, subscriptionId) -> {
                    statement.setObject(1, subscriptionId);
                    statement.setObject(2, customerId);
                    statement.setObject(3, planId);
                }
        );
        jdbc.update(
                "INSERT INTO renewal_outbox (id, subscription_id, due_date, payload) VALUES "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"confirm-a\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"confirm-b\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"confirm-c\"}'::jsonb)",
                A, subscriptionA, B, subscriptionB, C, subscriptionC
        );

        when(publisher.publish(anyString(), anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (B.toString().equals(id)) {
                return new CompletableFuture<Boolean>();
            }
            return CompletableFuture.completedFuture(true);
        });

        var parameters = new JobParametersBuilder()
                .addString("scheduleDate", "2026-01-01")
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters();
        double insertedBefore = registry.get("outbox.inserted").counter().count();
        double publishedBefore = registry.get("outbox.published").counter().count();
        var jobExecution = jobLauncher.run(renewalJob, parameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(registry.get("outbox.inserted").counter().count() - insertedBefore).isEqualTo(0.0);
        assertThat(registry.get("outbox.published").counter().count() - publishedBefore).isEqualTo(2.0);
        assertThat(isPublished(A)).isTrue();
        assertThat(isPublished(B)).isFalse();
        assertThat(isPublished(C)).isTrue();
        verify(publisher, times(1)).publish(eq(A.toString()), anyString());
        verify(publisher, times(1)).publish(eq(C.toString()), anyString());
        verify(publisher, times(2)).publish(eq(B.toString()), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(3L);
    }

    private boolean isPublished(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM renewal_outbox WHERE id = ?",
                Boolean.class,
                id
        ));
    }
}
