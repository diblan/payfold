package com.blanchaert.billing.producer;

import com.blanchaert.billing.producer.job.OutboxPublisher;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// D26 / R42: the scan window is [today - app.scanCatchUpDays, today + 1) at local
// midnight, so a night on which the job never launched is recovered by the next
// run under the ORIGINAL due-date keys. With a 2-day floor: yesterday and the
// floor day are in, the day beyond the floor and tomorrow are out, and a re-run
// mints nothing.
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.scanCatchUpDays=2"})
@Testcontainers
class ScanCatchUpWindowTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000400");
    private static final UUID DUE_TODAY = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID DUE_YESTERDAY = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID DUE_ON_FLOOR = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID DUE_BEYOND_FLOOR = UUID.fromString("00000000-0000-0000-0000-000000000404");
    private static final UUID DUE_TOMORROW = UUID.fromString("00000000-0000-0000-0000-000000000405");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    // The SYNC launcher: assertions depend on run() returning after the job finished.
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
    void windowReachesBackToTheFloorAndMintsOriginalDueDateKeys() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        Map<UUID, LocalDate> cohort = new LinkedHashMap<>();
        cohort.put(DUE_TODAY, today);
        cohort.put(DUE_YESTERDAY, today.minusDays(1));
        cohort.put(DUE_ON_FLOOR, today.minusDays(2));
        cohort.put(DUE_BEYOND_FLOOR, today.minusDays(3));
        cohort.put(DUE_TOMORROW, today.plusDays(1));

        jdbc.update("INSERT INTO customer (id, email, card_token) VALUES (?, ?, 'tok-producer-probe-01')",
                CUSTOMER_ID, "catch-up-probe@example.test");
        cohort.forEach(this::seedSubscriptionDueOn);
        when(publisher.publish(anyString(), anyString()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(true));

        double insertedBefore = counter("outbox.inserted");
        var run1 = jobLauncher.run(renewalJob, new JobParametersBuilder()
                .addString("scheduleDate", today.toString())
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters());
        assertThat(run1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(counter("outbox.inserted") - insertedBefore).isEqualTo(3.0);

        Map<UUID, LocalDate> minted = new LinkedHashMap<>();
        jdbc.query("SELECT subscription_id, due_date, payload->>'idempotency_key' AS key, "
                        + "payload->>'due_date' AS payload_due FROM renewal_outbox ORDER BY due_date",
                rs -> {
                    UUID subscriptionId = (UUID) rs.getObject("subscription_id");
                    LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
                    assertThat(rs.getString("key")).isEqualTo("sub-" + subscriptionId + "|" + dueDate);
                    assertThat(rs.getString("payload_due")).isEqualTo(dueDate.toString());
                    minted.put(subscriptionId, dueDate);
                });
        assertThat(minted).containsExactlyInAnyOrderEntriesOf(Map.of(
                DUE_TODAY, today,
                DUE_YESTERDAY, today.minusDays(1),
                DUE_ON_FLOOR, today.minusDays(2)));
        assertThat(minted).doesNotContainKeys(DUE_BEYOND_FLOOR, DUE_TOMORROW);

        // The detector D26 defines (active, overdue by local date) flags exactly the
        // row beyond the floor — which is the point of having a floor.
        assertThat(jdbc.query(
                "SELECT s.id FROM subscription s JOIN plan p ON p.id = s.plan_id "
                        + "WHERE s.status = 'active' AND s.renewed_at IS NOT NULL "
                        + "AND ((s.renewed_at + ('1 ' || p.interval)::interval) AT TIME ZONE ?)::date < ?",
                (rs, i) -> (UUID) rs.getObject("id"), ZONE.getId(), today))
                .containsExactlyInAnyOrder(DUE_YESTERDAY, DUE_ON_FLOOR, DUE_BEYOND_FLOOR);

        double insertedBeforeRerun = counter("outbox.inserted");
        var run2 = jobLauncher.run(renewalJob, new JobParametersBuilder()
                .addString("scheduleDate", today.toString())
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters());
        assertThat(run2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(counter("outbox.inserted") - insertedBeforeRerun).isEqualTo(0.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(3L);
    }

    // Due at 09:00 local on the target day: pick the plan interval whose
    // subtract-then-add round-trip reproduces the due instant (the clamp-day rule
    // of ClampDayDuePreimageTest), evaluated in Postgres so the seed and the scan
    // agree on the calendar arithmetic.
    private void seedSubscriptionDueOn(UUID subscriptionId, LocalDate dueDay) {
        jdbc.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                SELECT ?, ?, p.id, 'active', d.due_at - ('1 ' || p.interval)::interval
                FROM (SELECT (?::date + time '09:00') AT TIME ZONE ? AS due_at) d
                JOIN plan p ON p.interval = CASE
                    WHEN (d.due_at - interval '1 month') + interval '1 month' = d.due_at THEN 'month'
                    ELSE 'year' END
                ORDER BY p.name LIMIT 1
                """, subscriptionId, CUSTOMER_ID, dueDay, ZONE.getId());
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }
}
