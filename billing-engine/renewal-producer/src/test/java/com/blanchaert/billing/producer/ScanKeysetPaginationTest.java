package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.scanPageSize=2"})
@Testcontainers
class ScanKeysetPaginationTest {
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID SUBSCRIPTION_1 = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SUBSCRIPTION_2 = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID SUBSCRIPTION_3 = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID SUBSCRIPTION_4 = UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final UUID SUBSCRIPTION_5 = UUID.fromString("00000000-0000-0000-0000-000000000205");
    private static final UUID NEVER_RENEWED = UUID.fromString("00000000-0000-0000-0000-000000000206");
    private static final List<UUID> DUE_SUBSCRIPTIONS = List.of(
            SUBSCRIPTION_1,
            SUBSCRIPTION_2,
            SUBSCRIPTION_3,
            SUBSCRIPTION_4,
            SUBSCRIPTION_5
    );

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

    @Autowired
    private MeterRegistry registry;

    @MockitoBean
    private OutboxPublisher publisher;

    @Test
    void scansInKeysetPagesAndDeduplicatesAFullRescan() throws Exception {
        Plan plan = jdbc.queryForObject(
                "SELECT id, price_cents, currency FROM plan WHERE interval = 'month' ORDER BY name LIMIT 1",
                (rs, rowNum) -> new Plan(
                        (UUID) rs.getObject("id"),
                        rs.getLong("price_cents"),
                        rs.getString("currency")
                )
        );

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", CUSTOMER_ID, "keyset-probe@example.test");
        jdbc.batchUpdate(
                "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) "
                        + "VALUES (?, ?, ?, 'active', now() - INTERVAL '1 month')",
                DUE_SUBSCRIPTIONS,
                DUE_SUBSCRIPTIONS.size(),
                (statement, subscriptionId) -> {
                    statement.setObject(1, subscriptionId);
                    statement.setObject(2, CUSTOMER_ID);
                    statement.setObject(3, plan.id());
                }
        );
        jdbc.update(
                "INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at) "
                        + "VALUES (?, ?, ?, 'active', NULL)",
                NEVER_RENEWED,
                CUSTOMER_ID,
                plan.id()
        );
        when(publisher.publish(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(true));

        double insertedBeforeRun1 = counter("outbox.inserted");
        double publishedBeforeRun1 = counter("outbox.published");
        var run1 = jobLauncher.run(
                renewalJob,
                new JobParametersBuilder()
                        .addString("scheduleDate", "2026-01-01")
                        .addLong("run.id", ThreadLocalRandom.current().nextLong())
                        .toJobParameters()
        );

        assertThat(run1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var scanStep = run1.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals("scanStep"))
                .findFirst()
                .orElseThrow();
        assertThat(scanStep.getCommitCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT subscription_id) FROM renewal_outbox",
                Long.class
        )).isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM renewal_outbox WHERE subscription_id = ?",
                Long.class,
                NEVER_RENEWED
        )).isZero();
        assertThat(jdbc.query(
                "SELECT subscription_id FROM renewal_outbox",
                (rs, rowNum) -> (UUID) rs.getObject("subscription_id")
        )).containsExactlyInAnyOrderElementsOf(DUE_SUBSCRIPTIONS);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM renewal_outbox WHERE published_at IS NOT NULL",
                Long.class
        )).isEqualTo(5L);
        assertThat(counter("outbox.inserted") - insertedBeforeRun1).isEqualTo(5.0);
        assertThat(counter("outbox.published") - publishedBeforeRun1).isEqualTo(5.0);

        PayloadProbe probe = jdbc.queryForObject(
                "SELECT id, due_date, payload::text FROM renewal_outbox WHERE subscription_id = ?",
                (rs, rowNum) -> new PayloadProbe(
                        (UUID) rs.getObject("id"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getString("payload")
                ),
                SUBSCRIPTION_1
        );
        JsonNode payload = new ObjectMapper().readTree(probe.payload());
        String dueDate = LocalDate.now(ZoneId.of("Europe/Brussels")).toString();
        assertThat(payload.get("schema_version").isNumber()).isTrue();
        assertThat(payload.get("schema_version").asInt()).isEqualTo(1);
        assertThat(payload.get("event_id").asText()).isEqualTo(probe.id().toString());
        assertThat(payload.get("subscription_id").asText()).isEqualTo(SUBSCRIPTION_1.toString());
        assertThat(payload.get("customer_id").asText()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(payload.get("plan_id").asText()).isEqualTo(plan.id().toString());
        assertThat(payload.get("interval").asText()).isEqualTo("month");
        assertThat(payload.get("amount_cents").asLong()).isEqualTo(plan.priceCents());
        assertThat(payload.get("currency").asText()).isEqualTo(plan.currency());
        assertThat(payload.get("due_date").asText()).isEqualTo(dueDate);
        assertThat(probe.dueDate().toString()).isEqualTo(dueDate);
        assertThat(payload.get("idempotency_key").asText())
                .isEqualTo("sub-" + SUBSCRIPTION_1 + "|" + dueDate);
        assertThat(payload.get("period_start").asText()).isEqualTo(dueDate);
        assertThat(payload.get("period_end").asText())
                .isEqualTo(LocalDate.parse(dueDate).plusMonths(1).toString());
        assertThat(payload.get("occurred_at").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

        double insertedBeforeRun2 = counter("outbox.inserted");
        double publishedBeforeRun2 = counter("outbox.published");
        var run2 = jobLauncher.run(
                renewalJob,
                new JobParametersBuilder()
                        .addString("scheduleDate", "2026-01-01")
                        .addLong("run.id", ThreadLocalRandom.current().nextLong())
                        .toJobParameters()
        );

        assertThat(run2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(counter("outbox.inserted") - insertedBeforeRun2).isEqualTo(0.0);
        assertThat(counter("outbox.published") - publishedBeforeRun2).isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(5L);
        verify(publisher, times(5)).publish(anyString(), anyString());
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private record Plan(UUID id, long priceCents, String currency) {
    }

    private record PayloadProbe(UUID id, LocalDate dueDate, String payload) {
    }
}
