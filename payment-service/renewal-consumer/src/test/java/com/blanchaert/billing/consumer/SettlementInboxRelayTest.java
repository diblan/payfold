package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.config.RelayProperties;
import com.blanchaert.billing.consumer.config.SettlementTopology;
import com.blanchaert.billing.consumer.mq.SettlementInboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Direct-instance coverage of the D24 relay semantics: bounded in-flight
 * window, per-row confirm gating of published_at, and loud nack/deadline
 * behavior with next-tick re-pick. All broker behavior is a mocked
 * RabbitTemplate whose answers complete confirm futures synchronously, so no
 * test has a timing surface beyond the deliberately short page deadline; the
 * real-broker end-to-end path stays covered by SettlementSpineIntegrationTest.
 */
@Testcontainers
class SettlementInboxRelayTest {

    @Container
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.update("DELETE FROM settlement_inbox");
        rabbitTemplate = mock(RabbitTemplate.class);
    }

    @Test
    void acksMarkEveryRowOfThePage() {
        stubBroker(notificationId -> new CorrelationData.Confirm(true, null));
        seedInboxRow("n1");
        seedInboxRow("n2");
        seedInboxRow("n3");
        seedInboxRow("n4");
        seedInboxRow("n5");

        relay(100, 4, 2000).relayOnce();

        assertThat(publishedCount()).isEqualTo(5);
        assertThat(unpublishedCount()).isZero();
        verifySendCount(5);
    }

    @Test
    void windowBoundsInFlightSendsWhenConfirmsStall() {
        // Futures never complete: the first two sends fill the window and the
        // third blocks until the page deadline; nothing may be marked.
        stubBroker(notificationId -> null);
        seedInboxRow("n1");
        seedInboxRow("n2");
        seedInboxRow("n3");
        seedInboxRow("n4");
        seedInboxRow("n5");

        relay(5, 2, 250).relayOnce();

        verifySendCount(2);
        assertThat(publishedCount()).isZero();
    }

    @Test
    void nackedRowStaysUnpublishedWhileAckedSiblingsMarkAndNextTickRepicks() {
        stubBroker(notificationId -> "n2".equals(notificationId)
                ? new CorrelationData.Confirm(false, "test-nack")
                : new CorrelationData.Confirm(true, null));
        seedInboxRow("n1");
        UUID nacked = seedInboxRow("n2");
        seedInboxRow("n3");

        SettlementInboxRelay relay = relay(100, 4, 2000);
        relay.relayOnce();

        assertThat(publishedCount()).isEqualTo(2);
        assertThat(publishedAt(nacked)).isNull();
        verifySendCount(3);

        stubBroker(notificationId -> new CorrelationData.Confirm(true, null));
        relay.relayOnce();

        assertThat(publishedAt(nacked)).isNotNull();
        assertThat(publishedCount()).isEqualTo(3);
        verifySendCount(4);
    }

    @Test
    void unconfirmedRowStaysUnpublishedWhileConfirmedSiblingsMark() {
        // n2's confirm never arrives; n1 and n3 ack synchronously. Past the
        // deadline the await still marks every confirmed sibling regardless of
        // position, and only n2 waits for the next tick.
        stubBroker(notificationId -> "n2".equals(notificationId)
                ? null
                : new CorrelationData.Confirm(true, null));
        seedInboxRow("n1");
        UUID unconfirmed = seedInboxRow("n2");
        seedInboxRow("n3");

        relay(5, 3, 250).relayOnce();

        assertThat(publishedCount()).isEqualTo(2);
        assertThat(publishedAt(unconfirmed)).isNull();
        verifySendCount(3);
    }

    private interface ConfirmRule {
        CorrelationData.Confirm confirmFor(String notificationId);
    }

    /** A null confirm from the rule leaves that send's future incomplete. */
    private void stubBroker(ConfirmRule rule) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            String notificationId = objectMapper.readTree(message.getBody())
                    .get("notification_id").asText();
            CorrelationData correlation = invocation.getArgument(3);
            CorrelationData.Confirm confirm = rule.confirmFor(notificationId);
            if (confirm != null) {
                correlation.getFuture().complete(confirm);
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(SettlementTopology.EXCHANGE), eq(SettlementTopology.ROUTING_KEY),
                any(Message.class), any(CorrelationData.class));
    }

    private SettlementInboxRelay relay(int pageSize, int inFlightLimit, long confirmTimeoutMs) {
        return new SettlementInboxRelay(jdbc, rabbitTemplate, objectMapper,
                new RelayProperties(pageSize, inFlightLimit, confirmTimeoutMs));
    }

    private void verifySendCount(int expected) {
        verify(rabbitTemplate, times(expected)).convertAndSend(
                eq(SettlementTopology.EXCHANGE), eq(SettlementTopology.ROUTING_KEY),
                any(Message.class), any(CorrelationData.class));
    }

    private UUID seedInboxRow(String notificationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO settlement_inbox (id, bank_id, notification_id, payload)
                        VALUES (?, ?, ?, ?::jsonb)
                        """, id, "bank-a", notificationId,
                "{\"collection_id\": \"col-" + notificationId
                        + "\", \"outcome\": \"settled\", \"reason\": null,"
                        + " \"occurred_at\": \"2026-08-22T09:00:00Z\"}");
        return id;
    }

    private long publishedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_inbox WHERE published_at IS NOT NULL",
                Long.class);
    }

    private long unpublishedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_inbox WHERE published_at IS NULL",
                Long.class);
    }

    private java.sql.Timestamp publishedAt(UUID id) {
        return jdbc.queryForObject(
                "SELECT published_at FROM settlement_inbox WHERE id = ?",
                java.sql.Timestamp.class, id);
    }

    private static PostgreSQLContainer<?> postgresWithMigrations() {
        PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));
        Path moduleDirectory =
                Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path migrationDirectory = moduleDirectory
                .resolve("../../db-migrations")
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(migrationDirectory)) {
            throw new IllegalStateException(
                    "Migration directory not found: " + migrationDirectory);
        }

        try (var migrations = Files.list(migrationDirectory)) {
            migrations
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("V.*\\.sql"))
                    .sorted()
                    .forEach(path -> container.withCopyFileToContainer(
                            MountableFile.forHostPath(path.toString()),
                            "/docker-entrypoint-initdb.d/"
                                    + paddedMigrationName(path.getFileName().toString())));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not enumerate migrations in " + migrationDirectory,
                    exception);
        }

        return container;
    }

    // initdb executes /docker-entrypoint-initdb.d in C-locale filename order,
    // which puts V10 before V1; pad the version so lexical order is numeric.
    private static String paddedMigrationName(String fileName) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^V(\\d+)__(.*)$").matcher(fileName);
        if (!matcher.matches()) {
            return fileName;
        }
        return "V%03d__%s".formatted(Integer.parseInt(matcher.group(1)), matcher.group(2));
    }
}
