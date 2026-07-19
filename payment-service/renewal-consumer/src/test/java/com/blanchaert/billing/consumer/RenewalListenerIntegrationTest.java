package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    private ObjectMapper objectMapper;

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
                subscriptionId,
                customerId,
                planId,
                "month",
                1499,
                "EUR",
                idempotencyKey,
                periodStart.toString(),
                periodEnd.toString());
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
