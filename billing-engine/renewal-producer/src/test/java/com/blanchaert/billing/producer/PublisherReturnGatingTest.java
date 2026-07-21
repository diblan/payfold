package com.blanchaert.billing.producer;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "app.confirmTimeoutMs=250"})
@Testcontainers
class PublisherReturnGatingTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000001a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000001b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000001c");

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
    private RabbitTemplate rabbit;

    @Test
    void returnedMessageWinsOverAckAndIsRepicked() throws Exception {
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000200");
        UUID subscriptionA = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID subscriptionB = UUID.fromString("00000000-0000-0000-0000-000000000202");
        UUID subscriptionC = UUID.fromString("00000000-0000-0000-0000-000000000203");
        UUID planId = jdbc.queryForObject("SELECT id FROM plan ORDER BY name LIMIT 1", UUID.class);

        jdbc.update("INSERT INTO customer (id, email) VALUES (?, ?)", customerId, "return-probe@example.test");
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
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"return-a\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"return-b\"}'::jsonb), "
                        + "(?, ?, DATE '2026-01-01', '{\"probe\": \"return-c\"}'::jsonb)",
                A, subscriptionA, B, subscriptionB, C, subscriptionC
        );

        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (B.toString().equals(correlation.getId())) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE",
                        "billing.renewals", "renewal.requested"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        var parameters = new JobParametersBuilder()
                .addString("scheduleDate", "2026-01-01")
                .addLong("run.id", ThreadLocalRandom.current().nextLong())
                .toJobParameters();
        double insertedBefore = registry.get("outbox.inserted").counter().count();
        double publishedBefore = registry.get("outbox.published").counter().count();
        double returnedBefore = registry.get("outbox.returned").counter().count();
        var jobExecution = jobLauncher.run(renewalJob, parameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(registry.get("outbox.inserted").counter().count() - insertedBefore).isEqualTo(0.0);
        assertThat(registry.get("outbox.published").counter().count() - publishedBefore).isEqualTo(2.0);
        assertThat(registry.get("outbox.returned").counter().count() - returnedBefore).isEqualTo(2.0);
        assertThat(isPublished(A)).isTrue();
        assertThat(isPublished(B)).isFalse();
        assertThat(isPublished(C)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renewal_outbox", Long.class)).isEqualTo(3L);

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit, times(4)).convertAndSend(anyString(), anyString(), any(Message.class), correlationCaptor.capture());
        var capturedIds = correlationCaptor.getAllValues().stream().map(CorrelationData::getId).toList();
        assertThat(Collections.frequency(capturedIds, A.toString())).isEqualTo(1);
        assertThat(Collections.frequency(capturedIds, B.toString())).isEqualTo(2);
        assertThat(Collections.frequency(capturedIds, C.toString())).isEqualTo(1);
    }

    private boolean isPublished(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM renewal_outbox WHERE id = ?",
                Boolean.class,
                id
        ));
    }
}
