package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.config.SettlementTopology;
import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SettlementSpineIntegrationTest {
    private static final String BANK_ID = "bank-test";
    private static final String WEBHOOK_SECRET = "spine-test-secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    @Container
    @ServiceConnection
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management"));

    @Container
    static final GenericContainer<?> wireMock = new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:3.13.2"))
            .withExposedPorts(8080)
            .withCopyToContainer(
                    Transferable.of(readTestResource("bank/bank-collections-accept.json")),
                    "/home/wiremock/mappings/bank-collections-accept.json")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String wireMockUrl = "http://" + wireMock.getHost() + ":"
                + wireMock.getMappedPort(8080);
        registry.add("payment.provider.base-url", () -> wireMockUrl);
        registry.add("payment.provider.timeout-ms", () -> "1000");
        registry.add("bank.base-url", () -> wireMockUrl);
        registry.add("bank.timeout-ms", () -> "1000");
        registry.add("bank.id", () -> BANK_ID);
        registry.add("bank.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void duplicateWebhookInsertsOneRowAndFinalizesOnce() throws Exception {
        SubmittedPayment fixture = parkSubmitted("01");
        byte[] webhook = webhook(
                fixture.collectionId(), 1, "settled", null);

        assertThat(postWebhook(BANK_ID, webhook, sign(webhook, WEBHOOK_SECRET)))
                .isEqualTo(200);
        assertThat(postWebhook(BANK_ID, webhook, sign(webhook, WEBHOOK_SECRET)))
                .isEqualTo(200);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_inbox
                WHERE bank_id = ? AND notification_id = ?
                """, Long.class, BANK_ID, fixture.collectionId() + ":1")).isEqualTo(1L);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(paymentStatus(fixture)).isEqualTo("succeeded");
            assertThat(jdbc.queryForObject("""
                    SELECT completed_at IS NOT NULL FROM payment
                    WHERE collection_id = ?
                    """, Boolean.class, fixture.collectionId())).isTrue();
        });

        assertFinalized(fixture);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = ?",
                Long.class, fixture.customerId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM charge WHERE subscription_id = ?",
                Long.class, fixture.subscriptionId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE collection_id = ?",
                Long.class, fixture.collectionId())).isEqualTo(1L);
    }

    @Test
    void redeliveredSettlementMessageDoesNotDoubleFinalize() throws Exception {
        SubmittedPayment fixture = parkSubmitted("02");
        SettlementReceived event = settlement(
                fixture.collectionId(), 1, "settled", null);
        Message message = jsonMessage(objectMapper.writeValueAsBytes(event));

        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE, SettlementTopology.ROUTING_KEY, message);
        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE, SettlementTopology.ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("succeeded"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertFinalized(fixture);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM invoice WHERE customer_id = ?",
                    Long.class, fixture.customerId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM charge WHERE subscription_id = ?",
                    Long.class, fixture.subscriptionId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment WHERE collection_id = ?",
                    Long.class, fixture.collectionId())).isEqualTo(1L);
            assertThat(amqpAdmin.getQueueInfo(SettlementTopology.MAIN_QUEUE).getMessageCount())
                    .isZero();
            assertThat(amqpAdmin.getQueueInfo(SettlementTopology.DLQ).getMessageCount())
                    .isZero();
        });
    }

    @Test
    void poisonSettlementDeadLettersWhileGoodOnesFlow() throws Exception {
        parkSubmitted("03");
        SubmittedPayment good = parkSubmitted("04");
        byte[] poison = "not settlement json".getBytes(StandardCharsets.UTF_8);
        Message goodMessage = jsonMessage(objectMapper.writeValueAsBytes(
                settlement(good.collectionId(), 1, "settled", null)));

        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE,
                SettlementTopology.ROUTING_KEY,
                MessageBuilder.withBody(poison).build());
        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE, SettlementTopology.ROUTING_KEY, goodMessage);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(good)).isEqualTo("succeeded"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo(SettlementTopology.DLQ).getMessageCount())
                        .isEqualTo(1));

        Message deadLetter = rabbitTemplate.receive(SettlementTopology.DLQ, 5000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(poison);
        assertThat(amqpAdmin.getQueueInfo(SettlementTopology.DLQ).getMessageCount())
                .isZero();
    }

    @Test
    void webhookRejectsBadSignatureUnknownBankAndGarbage() throws Exception {
        jdbc.update("DELETE FROM settlement_inbox");
        byte[] validBody = webhook("collection-rejected", 1, "settled", null);
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);

        assertThat(postWebhook(BANK_ID, validBody, sign(validBody, "wrong-secret")))
                .isEqualTo(401);
        assertThat(postWebhook(BANK_ID, garbage, sign(garbage, WEBHOOK_SECRET)))
                .isEqualTo(400);
        assertThat(postWebhook(
                "unknown-bank", validBody, sign(validBody, WEBHOOK_SECRET)))
                .isEqualTo(404);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_inbox",
                Long.class)).isZero();
    }

    @Test
    void failedOutcomeIsTerminalWithReason() throws Exception {
        SubmittedPayment fixture = parkSubmitted("05");
        byte[] webhook = webhook(fixture.collectionId(), 1, "failed", "AM04");

        assertThat(postWebhook(BANK_ID, webhook, sign(webhook, WEBHOOK_SECRET)))
                .isEqualTo(200);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(paymentStatus(fixture)).isEqualTo("failed");
            assertThat(jdbc.queryForObject("""
                    SELECT failure_reason FROM payment WHERE collection_id = ?
                    """, String.class, fixture.collectionId())).isEqualTo("AM04");
            assertThat(jdbc.queryForObject("""
                    SELECT completed_at IS NOT NULL FROM payment
                    WHERE collection_id = ?
                    """, Boolean.class, fixture.collectionId())).isTrue();
        });

        assertThat(jdbc.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, fixture.subscriptionId())).isEqualTo("pending");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, fixture.customerId())).isEqualTo("posted");
        assertThat(renewedAt(fixture.subscriptionId())).isEqualTo(fixture.originalRenewedAt());
    }

    @Test
    void chargebackEndsSettledPaymentChargedBack() throws Exception {
        SubmittedPayment fixture = parkSubmitted("06");
        byte[] settled = webhook(fixture.collectionId(), 1, "settled", null);
        byte[] chargedBack = webhook(fixture.collectionId(), 2, "charged_back", "MD06");

        assertThat(postWebhook(BANK_ID, settled, sign(settled, WEBHOOK_SECRET)))
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("succeeded"));
        assertThat(postWebhook(BANK_ID, chargedBack, sign(chargedBack, WEBHOOK_SECRET)))
                .isEqualTo(200);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
            assertThat(jdbc.queryForObject("""
                    SELECT failure_reason FROM payment WHERE collection_id = ?
                    """, String.class, fixture.collectionId())).isEqualTo("MD06");
            assertThat(jdbc.queryForObject("""
                    SELECT charged_back_at IS NOT NULL FROM payment
                    WHERE collection_id = ?
                    """, Boolean.class, fixture.collectionId())).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM invoice WHERE customer_id = ?",
                    String.class, fixture.customerId())).isEqualTo("disputed");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM charge WHERE subscription_id = ?",
                    String.class, fixture.subscriptionId())).isEqualTo("settled");
            assertThat(renewedAt(fixture.subscriptionId()))
                    .isEqualTo(expectedRenewedAt(fixture.periodEnd()));
        });
    }

    @Test
    void chargebackRedeliveryIsIdempotent() throws Exception {
        SubmittedPayment fixture = parkSubmitted("07");
        byte[] settled = webhook(fixture.collectionId(), 1, "settled", null);
        byte[] chargedBack = webhook(fixture.collectionId(), 2, "charged_back", "MD06");

        assertThat(postWebhook(BANK_ID, settled, sign(settled, WEBHOOK_SECRET)))
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("succeeded"));
        assertThat(postWebhook(BANK_ID, chargedBack, sign(chargedBack, WEBHOOK_SECRET)))
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("charged_back"));
        Instant firstChargedBackAt = chargedBackAt(fixture);

        assertThat(postWebhook(BANK_ID, chargedBack, sign(chargedBack, WEBHOOK_SECRET)))
                .isEqualTo(200);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM settlement_inbox
                    WHERE bank_id = ? AND notification_id = ?
                    """, Long.class, BANK_ID, fixture.collectionId() + ":2")).isEqualTo(1L);
            assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
            assertThat(jdbc.queryForObject("""
                    SELECT failure_reason FROM payment WHERE collection_id = ?
                    """, String.class, fixture.collectionId())).isEqualTo("MD06");
            assertThat(chargedBackAt(fixture)).isEqualTo(firstChargedBackAt);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM invoice WHERE customer_id = ?",
                    String.class, fixture.customerId())).isEqualTo("disputed");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM charge WHERE subscription_id = ?",
                    String.class, fixture.subscriptionId())).isEqualTo("settled");
            assertThat(renewedAt(fixture.subscriptionId()))
                    .isEqualTo(expectedRenewedAt(fixture.periodEnd()));
        });
    }

    @Test
    void chargebackBeforeSettleStillWins() throws Exception {
        SubmittedPayment fixture = parkSubmitted("08");
        Message chargedBack = jsonMessage(objectMapper.writeValueAsBytes(
                settlement(fixture.collectionId(), 2, "charged_back", "MD06")));
        Message settled = jsonMessage(objectMapper.writeValueAsBytes(
                settlement(fixture.collectionId(), 1, "settled", null)));

        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE, SettlementTopology.ROUTING_KEY, chargedBack);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("charged_back"));

        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE, SettlementTopology.ROUTING_KEY, settled);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
            assertThat(renewedAt(fixture.subscriptionId()))
                    .isEqualTo(fixture.originalRenewedAt());
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM invoice WHERE customer_id = ?",
                    String.class, fixture.customerId())).isEqualTo("posted");
            assertThat(amqpAdmin.getQueueInfo(SettlementTopology.MAIN_QUEUE).getMessageCount())
                    .isZero();
            assertThat(amqpAdmin.getQueueInfo(SettlementTopology.DLQ).getMessageCount())
                    .isZero();
        });
    }

    private SubmittedPayment parkSubmitted(String ibanSuffix) throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID planId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'", UUID.class);
        LocalDate dueDate = LocalDate.of(2030, 1, 1);
        LocalDate periodEnd = dueDate.plusMonths(1);
        Instant originalRenewedAt = dueDate.minusMonths(1)
                .atTime(9, 0).atOffset(ZoneOffset.UTC).toInstant();
        String collectionId = "sub-" + subscriptionId + "|" + dueDate;

        jdbc.update("""
                INSERT INTO customer (id, email, name, status, payment_method,
                debtor_iban, mandate_reference, country)
                VALUES (?, ?, ?, 'active', 'sdd', ?, ?, 'BE')
                """, customerId, "settlement-" + customerId + "@example.com",
                "Settlement Test Customer", "BE68000000000000" + ibanSuffix,
                "MNDT-" + customerId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId,
                dueDate.minusMonths(1).atTime(9, 0).atOffset(ZoneOffset.UTC));

        RenewalRequested renewal = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month",
                1499, "EUR", collectionId, dueDate.toString(), dueDate.toString(),
                periodEnd.toString(), "2030-01-01T00:00:00.000Z");
        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                jsonMessage(objectMapper.writeValueAsBytes(renewal)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(collectionId)).isEqualTo("submitted"));
        return new SubmittedPayment(
                customerId, subscriptionId, collectionId, periodEnd, originalRenewedAt);
    }

    private byte[] webhook(
            String collectionId, int sequence, String outcome, String reason) {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return ("{\"schema_version\":1,\"bank_id\":\"" + BANK_ID
                + "\",\"notification_id\":\"" + collectionId + ":" + sequence
                + "\",\"collection_id\":\"" + collectionId
                + "\",\"outcome\":\"" + outcome + "\",\"reason\":" + reasonJson
                + ",\"occurred_at\":\"2030-01-01T00:00:00+00:00\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private SettlementReceived settlement(
            String collectionId, int sequence, String outcome, String reason) {
        return new SettlementReceived(
                1, collectionId + ":" + sequence, BANK_ID, collectionId,
                outcome, reason, "2030-01-01T00:00:00+00:00");
    }

    private int postWebhook(String bankId, byte[] body, String signature) {
        return RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/webhooks/bank/" + bankId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bank-Signature", signature)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private Message jsonMessage(byte[] body) {
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }

    private String paymentStatus(SubmittedPayment fixture) {
        return paymentStatus(fixture.collectionId());
    }

    private String paymentStatus(String collectionId) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE collection_id = ?",
                String.class, collectionId);
    }

    private void assertFinalized(SubmittedPayment fixture) {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, fixture.subscriptionId())).isEqualTo("settled");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, fixture.customerId())).isEqualTo("paid");
        assertThat(renewedAt(fixture.subscriptionId()))
                .isEqualTo(expectedRenewedAt(fixture.periodEnd()));
    }

    private Instant renewedAt(UUID subscriptionId) {
        return jdbc.queryForObject(
                "SELECT renewed_at FROM subscription WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), subscriptionId);
    }

    private Instant chargedBackAt(SubmittedPayment fixture) {
        return jdbc.queryForObject(
                "SELECT charged_back_at FROM payment WHERE collection_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), fixture.collectionId());
    }

    private Instant expectedRenewedAt(LocalDate periodEnd) {
        return Timestamp.valueOf(periodEnd.atTime(9, 0)).toInstant();
    }

    private static String readTestResource(String name) {
        try (InputStream resource =
                     SettlementSpineIntegrationTest.class.getClassLoader()
                             .getResourceAsStream(name)) {
            if (resource == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read test resource " + name, exception);
        }
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
                            "/docker-entrypoint-initdb.d/" + path.getFileName()));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not enumerate migrations in " + migrationDirectory,
                    exception);
        }

        return container;
    }

    private record SubmittedPayment(
            UUID customerId,
            UUID subscriptionId,
            String collectionId,
            LocalDate periodEnd,
            Instant originalRenewedAt) {
    }
}
