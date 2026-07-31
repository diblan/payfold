package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.blanchaert.billing.consumer.config.SettlementTopology;
import com.blanchaert.billing.consumer.service.BillingService;
import com.blanchaert.billing.consumer.service.InvalidRenewalMessageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.EmptyResultDataAccessException;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

    @Container
    static final GenericContainer<?> wireMock = new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:3.13.2"))
            .withExposedPorts(8080)
            .withCopyToContainer(Transferable.of(readTestResource("bank/bank-collections-accept.json")), "/home/wiremock/mappings/bank-collections-accept.json")
            .withCopyToContainer(Transferable.of(readTestResource("bank/card-collections.json")), "/home/wiremock/mappings/card-collections.json")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    @DynamicPropertySource
    static void counterpartyProperties(DynamicPropertyRegistry registry) {
        String wireMockUrl =
                "http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080);
        registry.add("bank.timeout-ms", () -> "1000");
        registry.add("bank.registry[0].id", () -> "bank-a");
        registry.add("bank.registry[0].scheme", () -> "sepa_core");
        registry.add("bank.registry[0].base-url", () -> wireMockUrl + "/bank-a");
        registry.add("bank.registry[0].webhook-secret", () -> "bank-a-secret");
        registry.add("bank.registry[0].countries", () -> "BE");
        registry.add("bank.registry[1].id", () -> "bank-b");
        registry.add("bank.registry[1].scheme", () -> "sepa_core");
        registry.add("bank.registry[1].base-url", () -> wireMockUrl + "/bank-b");
        registry.add("bank.registry[1].webhook-secret", () -> "bank-b-secret");
        registry.add("bank.registry[1].countries", () -> "NL");
        registry.add("bank.registry[2].id", () -> "cardnet");
        registry.add("bank.registry[2].scheme", () -> "card");
        registry.add("bank.registry[2].base-url", () -> wireMockUrl + "/cardnet");
        registry.add("bank.registry[2].webhook-secret", () -> "cardnet-secret");
    }

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

    @Autowired
    private MeterRegistry registry;

    @Test
    void listenerCreatesASucceededPayment() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + periodStart;

        insertCardCustomer(customerId, "tok-000000000001");
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

        double submittedBefore = registry.get("renewals.processed")
                .tag("outcome", "submitted")
                .tag("method", "card")
                .counter()
                .count();
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", message);

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long submittedPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, idempotencyKey);
            assertThat(submittedPayments).isEqualTo(1L);
        });
        publishSettlement(idempotencyKey, "cardnet", "settled", null, 1);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payment WHERE idempotency_key = ?",
                        String.class, idempotencyKey)).isEqualTo("succeeded"));

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(registry.get("renewals.processed")
                        .tag("outcome", "submitted")
                        .tag("method", "card")
                        .counter()
                        .count() - submittedBefore).isEqualTo(1.0));
    }

    @Test
    void providerDeclineMarksPaymentFailedWithoutFinalizing() throws JsonProcessingException {
        UUID failingCustomerId = UUID.randomUUID();
        UUID failingSubscriptionId = subscriptionIdEndingIn('0');
        UUID sentinelCustomerId = UUID.randomUUID();
        UUID sentinelSubscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate failingDueDate = LocalDate.of(2026, 11, 1);
        LocalDate failingPeriodEnd = failingDueDate.plusMonths(1);
        LocalDateTime originalRenewedAt = LocalDateTime.of(2026, 10, 1, 9, 0);
        String failingKey = "sub-" + failingSubscriptionId + "|" + failingDueDate;
        LocalDate sentinelDueDate = LocalDate.of(2026, 12, 1);
        String sentinelKey = "sub-" + sentinelSubscriptionId + "|" + sentinelDueDate;

        insertCardCustomer(failingCustomerId, "tok-000000000099");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, failingSubscriptionId, failingCustomerId, planId, originalRenewedAt.atOffset(ZoneOffset.UTC));
        insertCardCustomer(sentinelCustomerId, "tok-000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, sentinelSubscriptionId, sentinelCustomerId, planId,
                sentinelDueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested failingRenewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                failingSubscriptionId,
                failingCustomerId,
                planId,
                "month",
                1499,
                "EUR",
                failingKey,
                failingDueDate.toString(),
                failingDueDate.toString(),
                failingPeriodEnd.toString(),
                "2026-11-01T00:00:00.000Z");
        RenewalRequested sentinelRenewal = new RenewalRequested(
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
                "2026-12-01T00:00:00.000Z");

        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(failingRenewal))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
        rabbitTemplate.convertAndSend(
                "billing.renewals",
                "renewal.requested",
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(sentinelRenewal))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long sentinelPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, sentinelKey);
            assertThat(sentinelPayments).isEqualTo(1L);
        });
        publishSettlement(sentinelKey, "cardnet", "settled", null, 1);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payment WHERE idempotency_key = ?",
                        String.class, sentinelKey)).isEqualTo("succeeded"));

        Long failedPayments = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment WHERE idempotency_key = ?",
                Long.class, failingKey);
        assertThat(failedPayments).isEqualTo(1L);
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE idempotency_key = ?",
                String.class, failingKey);
        assertThat(paymentStatus).isEqualTo("failed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM payment WHERE idempotency_key = ?",
                String.class, failingKey)).isEqualTo("insufficient_funds");
        Boolean paymentCompleted = jdbcTemplate.queryForObject(
                "SELECT completed_at IS NOT NULL FROM payment WHERE idempotency_key = ?",
                Boolean.class, failingKey);
        assertThat(paymentCompleted).isTrue();

        String invoiceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, failingCustomerId);
        assertThat(invoiceStatus).isEqualTo("posted");
        String chargeStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, failingSubscriptionId);
        assertThat(chargeStatus).isEqualTo("pending");
        // renewed_at is TIMESTAMPTZ: compare instants so the JVM's zone cannot skew the read-back.
        Instant renewedAt = jdbcTemplate.queryForObject(
                "SELECT renewed_at FROM subscription WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), failingSubscriptionId);
        assertThat(renewedAt).isEqualTo(originalRenewedAt.atOffset(ZoneOffset.UTC).toInstant());

        awaitDb().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(
                () -> amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount() == 0);
        assertThat(amqpAdmin.getQueueInfo("billing.renewals.main").getMessageCount()).isZero();
        assertThat(bankRequestCount("cardnet", failingKey)).isEqualTo(1);
    }

    @Test
    void cardAuthTimeoutDeadLettersWithoutVerdict() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('e');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2027, 1, 1);
        LocalDate periodEnd = dueDate.plusMonths(1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + dueDate;

        insertCardCustomer(customerId, "tok-000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, dueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));

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
                "2027-01-01T00:00:00.000Z");
        Message message = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(renewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        String stub = """
                {"priority":5,"request":{"method":"POST","urlPath":"/cardnet/collections","bodyPatterns":[{"matchesJsonPath":{"expression":"$.collection_id","equalTo":"%s"}}]},"response":{"status":500}}
                """.formatted(idempotencyKey);
        RestClient.create()
                .post()
                .uri("http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080)
                        + "/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub)
                .retrieve()
                .toBodilessEntity();

        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", message);

        // The absence of an authorization verdict is not a decline.
        awaitDb().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount())
                        .isEqualTo(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isEqualTo("pending");
        String chargeStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, subscriptionId);
        assertThat(chargeStatus).isEqualTo("pending");
        String invoiceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, customerId);
        assertThat(invoiceStatus).isEqualTo("posted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT renewed_at < ? FROM subscription WHERE id = ?",
                Boolean.class, dueDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                subscriptionId)).isTrue();
        Message deadLetter = rabbitTemplate.receive("billing.renewals.dlq", 5000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(message.getBody());
        assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount()).isZero();
    }

    @Test
    void failedPaymentIsNotRetriedOnRedelivery() throws JsonProcessingException {
        UUID failingCustomerId = UUID.randomUUID();
        UUID failingSubscriptionId = subscriptionIdEndingIn('0');
        UUID sentinelCustomerId = UUID.randomUUID();
        UUID sentinelSubscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate failingDueDate = LocalDate.of(2027, 2, 1);
        String failingKey = "sub-" + failingSubscriptionId + "|" + failingDueDate;
        LocalDate sentinelDueDate = LocalDate.of(2027, 3, 1);
        String sentinelKey = "sub-" + sentinelSubscriptionId + "|" + sentinelDueDate;

        insertCardCustomer(failingCustomerId, "tok-000000000099");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, failingSubscriptionId, failingCustomerId, planId,
                failingDueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        insertCardCustomer(sentinelCustomerId, "tok-000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, sentinelSubscriptionId, sentinelCustomerId, planId,
                sentinelDueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested failingRenewal = new RenewalRequested(
                1,
                UUID.randomUUID(),
                failingSubscriptionId,
                failingCustomerId,
                planId,
                "month",
                1499,
                "EUR",
                failingKey,
                failingDueDate.toString(),
                failingDueDate.toString(),
                failingDueDate.plusMonths(1).toString(),
                "2027-02-01T00:00:00.000Z");
        Message failingMessage = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(failingRenewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        RenewalRequested sentinelRenewal = new RenewalRequested(
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
                "2027-03-01T00:00:00.000Z");
        Message sentinelMessage = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(sentinelRenewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", failingMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", failingMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", sentinelMessage);

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long sentinelPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, sentinelKey);
            assertThat(sentinelPayments).isEqualTo(1L);
        });
        publishSettlement(sentinelKey, "cardnet", "settled", null, 1);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payment WHERE idempotency_key = ?",
                        String.class, sentinelKey)).isEqualTo("succeeded"));

        Long failedPayments = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment
                WHERE idempotency_key = ? AND status = 'failed'
                """, Long.class, failingKey);
        assertThat(failedPayments).isEqualTo(1L);
        Long invoices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = ?",
                Long.class, failingCustomerId);
        assertThat(invoices).isEqualTo(1L);
        Long charges = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge WHERE subscription_id = ?",
                Long.class, failingSubscriptionId);
        assertThat(charges).isEqualTo(1L);
        assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount()).isZero();
        assertThat(bankRequestCount("cardnet", failingKey)).isEqualTo(1);
    }

    @Test
    void sddRenewalParksSubmittedWithoutFinalizing() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2027, 4, 1);
        LocalDate periodEnd = dueDate.plusMonths(1);
        LocalDateTime originalRenewedAt = LocalDateTime.of(2027, 3, 1, 9, 0);
        String idempotencyKey = "sub-" + subscriptionId + "|" + dueDate;

        insertSddCustomer(customerId, "BE6800000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId,
                originalRenewedAt.atOffset(ZoneOffset.UTC));

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
                "2027-04-01T00:00:00.000Z");
        Message message = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(renewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        double submittedBefore = registry.get("renewals.processed")
                .tag("outcome", "submitted")
                .tag("method", "sdd")
                .counter()
                .count();
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", message);

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long submittedPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, idempotencyKey);
            assertThat(submittedPayments).isEqualTo(1L);
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT channel FROM payment WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isEqualTo("SEPA_DD");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bank_id FROM payment WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isEqualTo("bank-a");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT collection_id FROM payment WHERE idempotency_key = ?",
                String.class, idempotencyKey)).isEqualTo(idempotencyKey);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT completed_at IS NULL FROM payment WHERE idempotency_key = ?",
                Boolean.class, idempotencyKey)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, customerId)).isEqualTo("posted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, subscriptionId)).isEqualTo("pending");
        Instant renewedAt = jdbcTemplate.queryForObject(
                "SELECT renewed_at FROM subscription WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), subscriptionId);
        assertThat(renewedAt).isEqualTo(originalRenewedAt.atOffset(ZoneOffset.UTC).toInstant());
        assertThat(bankRequestCount("cardnet", idempotencyKey)).isZero();
        assertThat(bankRequestCount("bank-a", idempotencyKey)).isEqualTo(1);

        awaitDb().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(
                () -> amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount() == 0
                        && amqpAdmin.getQueueInfo("billing.renewals.main").getMessageCount() == 0);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(registry.get("renewals.processed")
                        .tag("outcome", "submitted")
                        .tag("method", "sdd")
                        .counter()
                        .count() - submittedBefore).isEqualTo(1.0));
    }

    @Test
    void sddRoutingIsDeterministicByCountry() throws JsonProcessingException {
        UUID beCustomerId = UUID.randomUUID();
        UUID nlCustomerId = UUID.randomUUID();
        UUID beSubscriptionId = UUID.randomUUID();
        UUID nlSubscriptionId = UUID.randomUUID();
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'", UUID.class);
        LocalDate dueDate = LocalDate.of(2027, 4, 15);
        String beKey = "sub-" + beSubscriptionId + "|" + dueDate;
        String nlKey = "sub-" + nlSubscriptionId + "|" + dueDate;

        insertSddCustomer(beCustomerId, "BE6800000000000001", "BE");
        insertSddCustomer(nlCustomerId, "NL9100000000000001", "NL");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?), (?, ?, ?, 'active', ?)
                """,
                beSubscriptionId, beCustomerId, planId,
                dueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC),
                nlSubscriptionId, nlCustomerId, planId,
                dueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested beRenewal = new RenewalRequested(
                1, UUID.randomUUID(), beSubscriptionId, beCustomerId, planId, "month",
                1499, "EUR", beKey, dueDate.toString(), dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2027-04-15T00:00:00.000Z");
        RenewalRequested nlRenewal = new RenewalRequested(
                1, UUID.randomUUID(), nlSubscriptionId, nlCustomerId, planId, "month",
                1499, "EUR", nlKey, dueDate.toString(), dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2027-04-15T00:00:00.000Z");

        rabbitTemplate.convertAndSend(
                "billing.renewals", "renewal.requested",
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(beRenewal))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
        rabbitTemplate.convertAndSend(
                "billing.renewals", "renewal.requested",
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(nlRenewal))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT bank_id FROM payment WHERE idempotency_key = ?",
                    String.class, beKey)).isEqualTo("bank-a");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT bank_id FROM payment WHERE idempotency_key = ?",
                    String.class, nlKey)).isEqualTo("bank-b");
        });

        assertThat(bankRequestCount("bank-a", beKey)).isEqualTo(1);
        assertThat(bankRequestCount("bank-b", beKey)).isZero();
        assertThat(bankRequestCount("bank-b", nlKey)).isEqualTo(1);
        assertThat(bankRequestCount("bank-a", nlKey)).isZero();
    }

    @Test
    void sddRedeliveryYieldsExactlyOneSubmittedPayment() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID sentinelCustomerId = UUID.randomUUID();
        UUID sentinelSubscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2027, 5, 1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + dueDate;
        LocalDate sentinelDueDate = LocalDate.of(2027, 6, 1);
        String sentinelKey = "sub-" + sentinelSubscriptionId + "|" + sentinelDueDate;

        insertSddCustomer(customerId, "BE6800000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId,
                dueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        insertCardCustomer(sentinelCustomerId, "tok-000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, sentinelSubscriptionId, sentinelCustomerId, planId,
                sentinelDueDate.minusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        RenewalRequested renewal = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month",
                1499, "EUR", idempotencyKey, dueDate.toString(), dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2027-05-01T00:00:00.000Z");
        Message renewalMessage = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(renewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        RenewalRequested sentinel = new RenewalRequested(
                1, UUID.randomUUID(), sentinelSubscriptionId, sentinelCustomerId, planId,
                "month", 1499, "EUR", sentinelKey, sentinelDueDate.toString(),
                sentinelDueDate.toString(), sentinelDueDate.plusMonths(1).toString(),
                "2027-06-01T00:00:00.000Z");
        Message sentinelMessage = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(sentinel))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", renewalMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", renewalMessage);
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", sentinelMessage);

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long sentinelPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, sentinelKey);
            assertThat(sentinelPayments).isEqualTo(1L);
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment WHERE idempotency_key = ? AND status = 'submitted'",
                Long.class, idempotencyKey)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM invoice WHERE customer_id = ?",
                Long.class, customerId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM charge WHERE subscription_id = ?",
                Long.class, subscriptionId)).isEqualTo(1L);
        assertThat(bankRequestCount("bank-a", idempotencyKey)).isEqualTo(1);
        assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount()).isZero();
    }

    @Test
    void bankErrorLeavesPaymentPendingAndDeadLetters() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2027, 7, 1);
        LocalDateTime originalRenewedAt = LocalDateTime.of(2027, 6, 1, 9, 0);
        String failingKey = "sub-" + subscriptionId + "|" + dueDate;

        insertSddCustomer(customerId, "BE6800000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId,
                originalRenewedAt.atOffset(ZoneOffset.UTC));

        String stub = """
                {"priority":5,"request":{"method":"POST","urlPath":"/bank-a/collections","bodyPatterns":[{"matchesJsonPath":{"expression":"$.collection_id","equalTo":"%s"}}]},"response":{"status":500}}
                """.formatted(failingKey);
        RestClient.create()
                .post()
                .uri("http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080)
                        + "/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub)
                .retrieve()
                .toBodilessEntity();

        RenewalRequested renewal = new RenewalRequested(
                1, UUID.randomUUID(), subscriptionId, customerId, planId, "month",
                1499, "EUR", failingKey, dueDate.toString(), dueDate.toString(),
                dueDate.plusMonths(1).toString(), "2027-07-01T00:00:00.000Z");
        Message message = MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(renewal))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
        rabbitTemplate.convertAndSend("billing.renewals", "renewal.requested", message);

        awaitDb().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount())
                        .isEqualTo(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE idempotency_key = ?",
                String.class, failingKey)).isEqualTo("pending");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT bank_id IS NULL FROM payment WHERE idempotency_key = ?",
                Boolean.class, failingKey)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM invoice WHERE customer_id = ?",
                String.class, customerId)).isEqualTo("posted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM charge WHERE subscription_id = ?",
                String.class, subscriptionId)).isEqualTo("pending");
        Instant renewedAt = jdbcTemplate.queryForObject(
                "SELECT renewed_at FROM subscription WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), subscriptionId);
        assertThat(renewedAt).isEqualTo(originalRenewedAt.atOffset(ZoneOffset.UTC).toInstant());
        assertThat(bankRequestCount("cardnet", failingKey)).isZero();
        assertThat(bankRequestCount("bank-a", failingKey)).isEqualTo(5);

        Message deadLetter = rabbitTemplate.receive("billing.renewals.dlq", 5000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(message.getBody());
        assertThat(amqpAdmin.getQueueInfo("billing.renewals.dlq").getMessageCount()).isZero();
    }

    @Test
    void crossMidnightRedeliveryCreatesNoDuplicates() throws JsonProcessingException {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID sentinelCustomerId = UUID.randomUUID();
        UUID sentinelSubscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2026, 6, 15);
        LocalDate periodEnd = LocalDate.of(2026, 7, 15);
        String idempotencyKey = "sub-" + subscriptionId + "|2026-06-15";

        insertCardCustomer(customerId, "tok-000000000001");
        jdbcTemplate.update("""
                INSERT INTO subscription (id, customer_id, plan_id, status, renewed_at)
                VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, dueDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        insertCardCustomer(sentinelCustomerId, "tok-000000000001");
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

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long sentinelPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, sentinelKey);
            assertThat(sentinelPayments).isEqualTo(1L);
        });

        Long submittedPayments = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM payment
                WHERE idempotency_key = ? AND status = 'submitted'
                """, Long.class, idempotencyKey);
        assertThat(submittedPayments).isEqualTo(1L);
        publishSettlement(idempotencyKey, "cardnet", "settled", null, 1);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payment WHERE idempotency_key = ?",
                        String.class, idempotencyKey)).isEqualTo("succeeded"));

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
        UUID subscriptionId = subscriptionIdEndingIn('f');
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'",
                UUID.class);
        LocalDate dueDate = LocalDate.of(2026, 6, 15);

        insertCardCustomer(customerId, "tok-000000000001");
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
        UUID invalidSubscriptionId = subscriptionIdEndingIn('f');
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
        UUID subscriptionId = subscriptionIdEndingIn('f');
        LocalDate periodStart = LocalDate.of(2026, 10, 1);
        LocalDate periodEnd = periodStart.plusMonths(1);
        String idempotencyKey = "sub-" + subscriptionId + "|" + periodStart;

        insertCardCustomer(customerId, "tok-000000000001");
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

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long submittedPayments = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM payment
                    WHERE idempotency_key = ? AND status = 'submitted'
                    """, Long.class, idempotencyKey);
            assertThat(submittedPayments).isEqualTo(1L);
        });

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
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

    // A poll can fire before the consumer has inserted the row it queries;
    // a missing row must mean "not yet" (retry), never an instant abort.
    private static ConditionFactory awaitDb() {
        return await().ignoreExceptionsInstanceOf(EmptyResultDataAccessException.class);
    }

    private int bankRequestCount(String bank, String collectionId)
            throws JsonProcessingException {
        String request = """
                {"method":"POST","urlPath":"/%s/collections","bodyPatterns":[{"matchesJsonPath":{"expression":"$.collection_id","equalTo":"%s"}}]}
                """.formatted(bank, collectionId);
        String response = RestClient.create()
                .post()
                .uri("http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080) + "/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(response).path("count").asInt();
    }

    private void insertSddCustomer(UUID customerId, String debtorIban) {
        insertSddCustomer(customerId, debtorIban, "BE");
    }

    private void insertSddCustomer(
            UUID customerId, String debtorIban, String country) {
        jdbcTemplate.update("""
                INSERT INTO customer (id, email, name, status, payment_method,
                debtor_iban, mandate_reference, country)
                VALUES (?, ?, ?, 'active', 'sdd', ?, ?, ?)
                """, customerId, "sdd-test-" + customerId + "@example.com",
                // mandate_reference is VARCHAR(35) (the SEPA UMR maximum): a full
                // UUID suffix would overflow it, eight hex chars keep it unique enough.
                "SDD Test Customer", debtorIban,
                "MNDT-TEST-" + customerId.toString().substring(0, 8), country);
    }

    private void insertCardCustomer(UUID customerId, String cardToken) {
        jdbcTemplate.update("""
                INSERT INTO customer (
                    id, email, name, status, payment_method, card_token
                )
                VALUES (?, ?, ?, 'active', 'card', ?)
                """, customerId, "card-test-" + customerId + "@example.com",
                "Card Test Customer", cardToken);
    }

    private void publishSettlement(
            String collectionId, String bankId, String outcome,
            String reason, int sequence) throws JsonProcessingException {
        SettlementReceived settlement = new SettlementReceived(
                1,
                collectionId + ":" + sequence,
                bankId,
                collectionId,
                outcome,
                reason,
                "2030-01-01T00:00:00+00:00");
        rabbitTemplate.convertAndSend(
                SettlementTopology.EXCHANGE,
                SettlementTopology.ROUTING_KEY,
                MessageBuilder.withBody(objectMapper.writeValueAsBytes(settlement))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }

    // Pin ids for tests that exercise delivery timing independently of UUID shape.
    private static UUID subscriptionIdEndingIn(char lastHexChar) {
        String base = UUID.randomUUID().toString();
        return UUID.fromString(base.substring(0, base.length() - 1) + lastHexChar);
    }

    private static String readTestResource(String name) {
        try (InputStream resource = RenewalListenerIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (resource == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test resource " + name, exception);
        }
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
