package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.service.BillingService;
import com.blanchaert.billing.consumer.service.InvalidRenewalMessageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class RenewalListenerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BillingService billingService;

    @Test
    void listenerCreatesASucceededPayment() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + periodStart;

        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status)
                VALUES (?, ?, ?, 'active')
                """, customerId, "listener-test-" + customerId + "@example.com", "Listener Test Customer");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, periodStart.atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested renewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                subscriptionId,
                customerId,
                planId,
                "month",
                1499,
                "EUR",
                idempotencyKey,
                periodStart.toString(),
                periodStart.toString(),
                periodEnd.toString(),
                "2026-07-01T00:00:00.000Z");
        Message message = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(renewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", message);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long succeededPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'succeeded'
                    """, Long.class, idempotencyKey);
            assertThat(succeededPayments).isEqualTo(1L);
        });
    }

    @Test
    void crossMidnightRedeliveryCreatesNoDuplicates() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID sentinelCustomerId = UUID.randomUUID();
        UUID sentinelSubscriptionId = UUID.randomUUID();
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2026, 6, 15);
        LocalDate periodEnd = LocalDate.of(2026, 7, 15);
        String idempotencyKey = "sub-" + subscriptionId + "|2026-06-15";

        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status)
                VALUES (?, ?, ?, 'active')
                """, customerId, "midnight-test-" + customerId + "@example.com", "Midnight Test Customer");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, dueDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status)
                VALUES (?, ?, ?, 'active')
                """, sentinelCustomerId, "sentinel-test-" + sentinelCustomerId + "@example.com", "Sentinel Test Customer");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, sentinelSubscriptionId, sentinelCustomerId, planId,
                LocalDate.of(2026, 8, 15).atStartOfDay().atOffset(ZoneOffset.UTC));

        // These payload dates differ from the machine date: any surviving consume-time clock
        // dependency would mint a different key or period on redelivery: duplicates or wrong dates.
        RenewalRequested renewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                subscriptionId,
                customerId,
                planId,
                "month",
                1499,
                "EUR",
                idempotencyKey,
                dueDate.toString(),
                dueDate.toString(),
                periodEnd.toString(),
                "2026-06-15T00:00:00.000Z");
        byte[] renewalBytes = objectMapper.writeValueAsBytes(renewal);
        Message renewalMessage = MessageBuilder
                .withBody(renewalBytes)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        LocalDate sentinelDueDate = LocalDate.of(2026, 8, 15);
        String sentinelKey = "sub-" + sentinelSubscriptionId + "|2026-08-15";
        RenewalRequested sentinel = new RenewalRequested(
                1,
                UUID.randomUUID(),
                sentinelSubscriptionId,
                sentinelCustomerId,
                planId,
                "month",
                1499,
                "EUR",
                sentinelKey,
                sentinelDueDate.toString(),
                sentinelDueDate.toString(),
                sentinelDueDate.plusMonths(1).toString(),
                "2026-08-15T00:00:00.000Z");
        Message sentinelMessage = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(sentinel))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", renewalMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", renewalMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", sentinelMessage);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long sentinelPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'succeeded'
                    """, Long.class, sentinelKey);
            assertThat(sentinelPayments).isEqualTo(1L);
        });

        Long payments = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM payment
                WHERE idempotency_key = ? AND status = 'succeeded'
                """, Long.class, idempotencyKey);
        assertThat(payments).isEqualTo(1L);

        Long invoices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = ?",
                Long.class, customerId);
        assertThat(invoices).isEqualTo(1L);
        LocalDate storedPeriodStart = jdbcTemplate.queryForObject(
                "SELECT period_start FROM invoice WHERE customer_id = ?",
                (rs, rowNum) -> rs.getObject("period_start", LocalDate.class), customerId);
        LocalDate storedPeriodEnd = jdbcTemplate.queryForObject(
                "SELECT period_end FROM invoice WHERE customer_id = ?",
                (rs, rowNum) -> rs.getObject("period_end", LocalDate.class), customerId);
        assertThat(storedPeriodStart).isEqualTo(dueDate);
        assertThat(storedPeriodEnd).isEqualTo(periodEnd);

        Long charges = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge WHERE subscription_id = ?",
                Long.class, subscriptionId);
        assertThat(charges).isEqualTo(1L);
        LocalDate storedDueDate = jdbcTemplate.queryForObject(
                "SELECT due_date FROM charge WHERE subscription_id = ?",
                (rs, rowNum) -> rs.getObject("due_date", LocalDate.class), subscriptionId);
        assertThat(storedDueDate).isEqualTo(dueDate);
        assertThat(storedDueDate).isNotEqualTo(periodEnd);

        LocalDateTime renewedAt = jdbcTemplate.queryForObject(
                "SELECT renewed_at FROM subscription WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toLocalDateTime(), subscriptionId);
        assertThat(renewedAt).isEqualTo(LocalDateTime.of(2026, 7, 15, 9, 0));
    }

    @Test
    void invalidMessagesAreRejectedWithoutWrites() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2026, 6, 15);

        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status)
                VALUES (?, ?, ?, 'active')
                """, customerId, "invalid-test-" + customerId + "@example.com", "Invalid Test Customer");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, dueDate.atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested missingKey = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month", 1499, "EUR",
                null, dueDate.toString(), dueDate.toString(), dueDate.plusMonths(1).toString(),
                "2026-06-15T00:00:00.000Z");
        RenewalRequested missingDueDate = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month", 1499, "EUR",
                "sub-" + subscriptionId + "|2026-06-15", null, dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2026-06-15T00:00:00.000Z");
        RenewalRequested malformedDueDate = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month", 1499, "EUR",
                "sub-" + subscriptionId + "|2026-06-15", "not-a-date", dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2026-06-15T00:00:00.000Z");

        assertThatThrownBy(() -> billingService.process(missingKey))
                .isInstanceOf(InvalidRenewalMessageException.class);
        assertThatThrownBy(() -> billingService.process(missingDueDate))
                .isInstanceOf(InvalidRenewalMessageException.class);
        assertThatThrownBy(() -> billingService.process(malformedDueDate))
                .isInstanceOf(InvalidRenewalMessageException.class);

        Long invoices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = ?",
                Long.class, customerId);
        Long charges = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge WHERE subscription_id = ?",
                Long.class, subscriptionId);
        Long payments = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM payment p
                JOIN charge c ON c.id = p.charge_id
                WHERE c.subscription_id = ?
                """, Long.class, subscriptionId);
        assertThat(invoices).isZero();
        assertThat(charges).isZero();
        assertThat(payments).isZero();
    }

    @Test
    void poisonMessagesDeadLetterWhileGoodMessagesFlow() throws JsonProcessingException {
        byte[] malformedBody = "this is not json".getBytes(StandardCharsets.UTF_8);

        UUID invalidCustomerId = UUID.randomUUID();
        UUID invalidSubscriptionId = UUID.randomUUID();
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate invalidDueDate = LocalDate.of(2026, 9, 1);
        RenewalRequested invalidRenewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                invalidSubscriptionId,
                invalidCustomerId,
                planId,
                "month",
                1499,
                "EUR",
                null,
                invalidDueDate.toString(),
                invalidDueDate.toString(),
                invalidDueDate.plusMonths(1).toString(),
                "2026-09-01T00:00:00.000Z");
        byte[] invalidBody = objectMapper.writeValueAsBytes(invalidRenewal);

        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        LocalDate periodStart = LocalDate.of(2026, 10, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + periodStart;

        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status)
                VALUES (?, ?, ?, 'active')
                """, customerId, "poison-test-" + customerId + "@example.com", "Poison Test Customer");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, periodStart.atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested goodRenewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                subscriptionId,
                customerId,
                planId,
                "month",
                1499,
                "EUR",
                idempotencyKey,
                periodStart.toString(),
                periodStart.toString(),
                periodEnd.toString(),
                "2026-10-01T00:00:00.000Z");
        byte[] goodBody = objectMapper.writeValueAsBytes(goodRenewal);

        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                MessageBuilder.withBody(malformedBody).build());
        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                MessageBuilder.withBody(invalidBody)
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                MessageBuilder.withBody(goodBody)
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long succeededPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'succeeded'
                    """, Long.class, idempotencyKey);
            assertThat(succeededPayments).isEqualTo(1L);
        });

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount()).isEqualTo(2));

        Message firstDeadLetter = rabbitTemplate.receive("billing.renewals.dlq", 5000);
        Message secondDeadLetter = rabbitTemplate.receive("billing.renewals.dlq", 5000);
        assertThat(firstDeadLetter).isNotNull();
        assertThat(secondDeadLetter).isNotNull();
        assertThat(Set.of(
                new String(firstDeadLetter.getBody(), StandardCharsets.UTF_8),
                new String(secondDeadLetter.getBody(), StandardCharsets.UTF_8)))
                .isEqualTo(Set.of(
                        new String(malformedBody, StandardCharsets.UTF_8),
                        new String(invalidBody, StandardCharsets.UTF_8)));

        assertThat(amqpAdmin.getQueueInfo("billing.renewals.main").getMessageCount()).isZero();
    }

    private static PostgreSQLContainer<?> postgresWithMigrations() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));
        Path moduleDirectory = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path migrationDirectory = moduleDirectory
                .resolve("../../db-migrations")
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(migrationDirectory)) {
            throw new IllegalStateException("Migration directory not found: " + migrationDirectory);
        }

        try (var migrations = Files.list(migrationDirectory)) {
            migrations
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("V.*\\.sql"))
                    .sorted()
                    .forEach(path -> container.withCopyFileToContainer(
                            MountableFile.forHostPath(path.toString()),
                            "/docker-entrypoint-initdb.d/" + path.getFileName()));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not enumerate migrations in " + migrationDirectory, exception);
        }

        return container;
    }
}
