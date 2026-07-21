package com.blanchaert.billing.producer;

import io.micrometer.core.instrument.MeterRegistry;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.confirmTimeoutMs=2000"})
@Testcontainers
class UnroutableReturnIntegrationTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000002a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000002b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000002c");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management"));

    // The SYNC launcher: this test's assertions depend on run() returning only
    // after the job finished.
    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher jobLauncher;

    @Autowired
    private Job renewalJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void bindingLessExchangeReturnsEveryMessageAndLeavesRowsUnpublished() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000300");
        UUID subscriptionA = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID subscriptionB = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID subscriptionC = UUID.fromString("00000000-0000-0000-0000-000000000303");
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", customerId, "unroutable-probe@example.test");
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
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"unroutable-a\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"unroutable-b\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"unroutable-c\"}'::jsonb)",
                A, subscriptionA, B, subscriptionB, C, subscriptionC
        );

        // The application declares only the exchange. This test intentionally
        // declares no queue or binding because the absent binding is the scenario under test.
        var parameters = new JobParametersBuilder()
                .addString("scheduleDate", "2026-01-01")
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters();
        double publishedBefore = registry.get("outbox.published").counter().count();
        double returnedBefore = registry.get("outbox.returned").counter().count();
        var jobExecution = jobLauncher.run(renewalJob, parameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(registry.get("outbox.published").counter().count() - publishedBefore).isEqualTo(0.0);
        assertThat(registry.get("outbox.returned").counter().count() - returnedBefore).isEqualTo(3.0);
        assertThat(isPublished(A)).isFalse();
        assertThat(isPublished(B)).isFalse();
        assertThat(isPublished(C)).isFalse();
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
