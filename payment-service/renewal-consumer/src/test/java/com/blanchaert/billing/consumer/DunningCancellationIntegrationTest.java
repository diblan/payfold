package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.blanchaert.billing.consumer.service.DunningSweeper;
import com.blanchaert.billing.consumer.service.SettlementService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DunningCancellationIntegrationTest {
    private static final String BANK_A_ID = "bank-a";
    private static final String CARD_ID = "cardnet";

    // WireMock's Jetty admin port accepts the JDK HttpClient's h2c upgrade and
    // then cancels the stream; force plain HTTP/1.1 for every admin call.
    private static final RestClient adminClient = RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();

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
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String baseUrl = wireMockUrl();
        registry.add("bank.timeout-ms", () -> "1000");
        registry.add("bank.registry[0].id", () -> BANK_A_ID);
        registry.add("bank.registry[0].scheme", () -> "sepa_core");
        registry.add("bank.registry[0].base-url", () -> baseUrl + "/bank-a");
        registry.add("bank.registry[0].webhook-secret", () -> "bank-a-secret");
        registry.add("bank.registry[0].countries", () -> "BE,FR");
        registry.add("bank.registry[1].id", () -> "bank-b");
        registry.add("bank.registry[1].scheme", () -> "sepa_core");
        registry.add("bank.registry[1].base-url", () -> baseUrl + "/bank-b");
        registry.add("bank.registry[1].webhook-secret", () -> "bank-b-secret");
        registry.add("bank.registry[1].countries", () -> "NL,IE");
        registry.add("bank.registry[2].id", () -> CARD_ID);
        registry.add("bank.registry[2].scheme", () -> "card");
        registry.add("bank.registry[2].base-url", () -> baseUrl + "/cardnet");
        registry.add("bank.registry[2].webhook-secret", () -> "cardnet-secret");
        registry.add("dunning.retry-delay-seconds", () -> "5");
        registry.add("dunning.max-attempts", () -> "3");
        registry.add("dunning.sweep-interval-ms", () -> "3600000");
        registry.add("recovery.stale-after-seconds", () -> "3600");
        registry.add("recovery.sweep-interval-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DunningSweeper dunningSweeper;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private MeterRegistry meters;

    private final List<PaymentFixture> fixtures = new ArrayList<>();

    @BeforeEach
    void resetWireMock() {
        adminClient.delete()
                .uri(wireMockUrl() + "/__admin/mappings")
                .retrieve()
                .toBodilessEntity();
        adminClient.delete()
                .uri(wireMockUrl() + "/__admin/requests")
                .retrieve()
                .toBodilessEntity();
    }

    @AfterEach
    void cleanFixtures() {
        for (PaymentFixture fixture : fixtures) {
            jdbc.update("DELETE FROM payment WHERE charge_id = ?", fixture.chargeId());
            jdbc.update("DELETE FROM charge WHERE id = ?", fixture.chargeId());
            jdbc.update("DELETE FROM invoice WHERE id = ?", fixture.invoiceId());
            jdbc.update("DELETE FROM subscription WHERE id = ?", fixture.subscriptionId());
            jdbc.update("DELETE FROM customer WHERE id = ?", fixture.customerId());
        }
        fixtures.clear();
    }

    @Test
    void exhaustedRetriableIsCanceled() {
        PaymentFixture fixture = seedPayment(
                "SEPA_DD", "past_due", "failed", "AM04",
                Instant.now().plusSeconds(600));
        age(fixture);
        addAttempt(fixture, 3, "failed", "AM04");
        double exhaustedBefore = cancellationCount("exhausted");
        double expiredBefore = cancellationCount("grace_expired");

        dunningSweeper.sweepOnce();

        SubscriptionState canceled = subscriptionState(fixture);
        assertThat(canceled.status()).isEqualTo("canceled");
        assertThat(canceled.graceUntil()).isNull();
        assertThat(cancellationCount("exhausted") - exhaustedBefore).isEqualTo(1.0);
        assertThat(cancellationCount("grace_expired") - expiredBefore).isEqualTo(0.0);
        assertThat(collectionPostCount()).isZero();
        assertThat(attemptCount(fixture, 4)).isZero();
        long paymentsAfterFirstSweep = paymentCount(fixture);

        dunningSweeper.sweepOnce();

        assertThat(subscriptionState(fixture)).isEqualTo(canceled);
        assertThat(paymentCount(fixture)).isEqualTo(paymentsAfterFirstSweep);
        assertThat(cancellationCount("exhausted") - exhaustedBefore).isEqualTo(1.0);
        assertThat(cancellationCount("grace_expired") - expiredBefore).isEqualTo(0.0);
        assertThat(collectionPostCount()).isZero();
        assertThat(attemptCount(fixture, 4)).isZero();
    }

    @Test
    void graceExpiryCancelsHardFailAndDispute() {
        PaymentFixture hardFail = seedPayment(
                "SEPA_DD", "past_due", "failed", "AC04",
                Instant.now().minusSeconds(60));
        PaymentFixture dispute = seedPayment(
                "SEPA_DD", "past_due", "charged_back", "MD06",
                Instant.now().minusSeconds(60));
        double exhaustedBefore = cancellationCount("exhausted");
        double expiredBefore = cancellationCount("grace_expired");

        dunningSweeper.sweepOnce();

        assertCanceledWithoutGrace(hardFail);
        assertCanceledWithoutGrace(dispute);
        assertThat(cancellationCount("grace_expired") - expiredBefore).isEqualTo(2.0);
        assertThat(cancellationCount("exhausted") - exhaustedBefore).isEqualTo(0.0);
        assertThat(collectionPostCount()).isZero();
    }

    @Test
    void belowMaxRetriesInsteadOfCanceling() {
        PaymentFixture fixture = seedPayment(
                "SEPA_DD", "past_due", "failed", "AM04",
                Instant.now().plusSeconds(600));
        age(fixture);
        String retryId = fixture.collectionId() + "|a2";
        stubAccepted("/bank-a/collections", retryId);
        double exhaustedBefore = cancellationCount("exhausted");
        double expiredBefore = cancellationCount("grace_expired");

        dunningSweeper.sweepOnce();

        assertThat(paymentState(fixture, 2).status()).isEqualTo("submitted");
        assertThat(subscriptionState(fixture).status()).isEqualTo("past_due");
        assertThat(cancellationCount("exhausted") - exhaustedBefore).isEqualTo(0.0);
        assertThat(cancellationCount("grace_expired") - expiredBefore).isEqualTo(0.0);
    }

    @Test
    void unexpiredGraceIsUntouched() {
        PaymentFixture fixture = seedPayment(
                "SEPA_DD", "past_due", "failed", "AC04",
                Instant.now().plusSeconds(600));
        SubscriptionState before = subscriptionState(fixture);
        double exhaustedBefore = cancellationCount("exhausted");
        double expiredBefore = cancellationCount("grace_expired");

        dunningSweeper.sweepOnce();

        assertThat(subscriptionState(fixture)).isEqualTo(before);
        assertThat(collectionPostCount()).isZero();
        assertThat(cancellationCount("exhausted") - exhaustedBefore).isEqualTo(0.0);
        assertThat(cancellationCount("grace_expired") - expiredBefore).isEqualTo(0.0);
    }

    @Test
    void canceledIsInvisibleToTheSweeper() {
        PaymentFixture fixture = seedPayment(
                "SEPA_DD", "canceled", "failed", "AM04", null);
        age(fixture);

        dunningSweeper.sweepOnce();

        assertThat(paymentCount(fixture)).isEqualTo(1L);
        assertThat(collectionPostCount()).isZero();
        assertCanceledWithoutGrace(fixture);
    }

    @Test
    void lateSettlementDoesNotResurrectCanceled() {
        PaymentFixture fixture = seedPayment(
                "SEPA_DD", "canceled", "failed", "AM04", null);
        String retryId = fixture.collectionId() + "|a2";
        seedSubmittedRetry(fixture, retryId);
        SettlementReceived settled = new SettlementReceived(
                1, retryId + ":1", BANK_A_ID, retryId,
                "settled", null, Instant.now().toString());
        double recoveriesBefore = recoveryCount();

        settlementService.apply(settled);

        assertThat(paymentState(fixture, 2).status()).isEqualTo("succeeded");
        assertCanceledWithoutGrace(fixture);
        assertThat(recoveryCount() - recoveriesBefore).isEqualTo(0.0);

        settlementService.apply(settled);

        assertThat(paymentState(fixture, 2).status()).isEqualTo("succeeded");
        assertCanceledWithoutGrace(fixture);
        assertThat(recoveryCount() - recoveriesBefore).isEqualTo(0.0);
    }

    private PaymentFixture seedPayment(
            String channel, String subscriptionStatus, String paymentStatus,
            String failureReason, Instant graceUntil) {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID chargeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID planId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'", UUID.class);
        LocalDate dueDate = LocalDate.of(2033, 8, 1);
        long amountCents = 1499L;
        String currency = "EUR";
        boolean card = "CARD".equals(channel);
        String debtorIban = card ? null : "BE6800000000000095";
        String mandateReference = card ? null : "MNDT-DUNNING-CANCEL";
        String cardToken = card ? "tok-0000000095" : null;
        String bankId = card ? CARD_ID : BANK_A_ID;
        String collectionId = "sub-" + subscriptionId + "|" + dueDate;
        Timestamp graceDeadline = graceUntil == null ? null : Timestamp.from(graceUntil);

        jdbc.update("""
                INSERT INTO customer (
                    id, email, name, status, payment_method, debtor_iban,
                    mandate_reference, country, card_token
                ) VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?)
                """, customerId, "dunning-cancel-" + customerId + "@example.com",
                "Dunning Cancellation Test Customer", card ? "card" : "sdd",
                debtorIban, mandateReference, card ? null : "BE", cardToken);
        jdbc.update("""
                INSERT INTO subscription (
                    id, customer_id, plan_id, status, renewed_at, grace_until
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, subscriptionId, customerId, planId, subscriptionStatus,
                dueDate.minusMonths(1).atTime(9, 0).atOffset(ZoneOffset.UTC),
                graceDeadline);
        jdbc.update("""
                INSERT INTO invoice (
                    id, customer_id, period_start, period_end, total_cents,
                    currency, number, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'posted')
                """, invoiceId, customerId, dueDate, dueDate.plusMonths(1),
                amountCents, currency, "INV-" + invoiceId);
        jdbc.update("""
                INSERT INTO charge (
                    id, subscription_id, invoice_id, amount_cents, currency,
                    description, status, due_date
                ) VALUES (?, ?, ?, ?, ?, 'Dunning cancellation test charge', 'pending', ?)
                """, chargeId, subscriptionId, invoiceId, amountCents, currency, dueDate);
        jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, requested_at, completed_at,
                    bank_id, collection_id, failure_reason, attempt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), ?, ?, ?, 1)
                """, paymentId, chargeId, amountCents, currency, channel,
                collectionId, paymentStatus, bankId, collectionId, failureReason);

        PaymentFixture fixture = new PaymentFixture(
                customerId, subscriptionId, invoiceId, chargeId, paymentId,
                collectionId, dueDate, amountCents, currency, channel, bankId);
        fixtures.add(fixture);
        return fixture;
    }

    private void addAttempt(
            PaymentFixture fixture, int attempt, String status, String reason) {
        String collectionId = fixture.collectionId() + "|a" + attempt;
        jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, requested_at, completed_at,
                    bank_id, collection_id, failure_reason, attempt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now(),
                          now() - interval '10 minutes', ?, ?, ?, ?)
                """, UUID.randomUUID(), fixture.chargeId(), fixture.amountCents(),
                fixture.currency(), fixture.channel(), collectionId, status,
                fixture.bankId(), collectionId, reason, attempt);
    }

    private void seedSubmittedRetry(PaymentFixture fixture, String collectionId) {
        jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, requested_at, bank_id,
                    collection_id, attempt
                ) VALUES (?, ?, ?, ?, 'SEPA_DD', ?, 'submitted', now(), ?, ?, 2)
                """, UUID.randomUUID(), fixture.chargeId(), fixture.amountCents(),
                fixture.currency(), collectionId, BANK_A_ID, collectionId);
    }

    private void age(PaymentFixture fixture) {
        jdbc.update("""
                UPDATE payment
                SET completed_at = now() - interval '10 minutes'
                WHERE id = ?
                """, fixture.paymentId());
    }

    private PaymentState paymentState(PaymentFixture fixture, int attempt) {
        return jdbc.queryForObject("""
                        SELECT status, failure_reason, collection_id
                        FROM payment
                        WHERE charge_id = ? AND attempt = ?
                        """,
                (rs, rowNum) -> new PaymentState(
                        rs.getString("status"),
                        rs.getString("failure_reason"),
                        rs.getString("collection_id")),
                fixture.chargeId(), attempt);
    }

    private long attemptCount(PaymentFixture fixture, int attempt) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE charge_id = ? AND attempt = ?",
                Long.class, fixture.chargeId(), attempt);
    }

    private long paymentCount(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE charge_id = ?",
                Long.class, fixture.chargeId());
    }

    private SubscriptionState subscriptionState(PaymentFixture fixture) {
        return jdbc.queryForObject("""
                        SELECT status, grace_until, renewed_at
                        FROM subscription WHERE id = ?
                        """,
                (rs, rowNum) -> new SubscriptionState(
                        rs.getString("status"),
                        instant(rs.getTimestamp("grace_until")),
                        instant(rs.getTimestamp("renewed_at"))),
                fixture.subscriptionId());
    }

    private void assertCanceledWithoutGrace(PaymentFixture fixture) {
        SubscriptionState subscription = subscriptionState(fixture);
        assertThat(subscription.status()).isEqualTo("canceled");
        assertThat(subscription.graceUntil()).isNull();
    }

    private void stubAccepted(String path, String collectionId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("collection_id", collectionId);
        body.put("status", "accepted");
        body.put("duplicate", false);
        stubResponse(path, 202, body);
    }

    private void stubResponse(String path, int status, JsonNode body) {
        ObjectNode mapping = objectMapper.createObjectNode();
        ObjectNode request = mapping.putObject("request");
        request.put("method", "POST");
        request.put("urlPath", path);
        ObjectNode response = mapping.putObject("response");
        response.put("status", status);
        response.set("jsonBody", body);
        response.putObject("headers").put("Content-Type", "application/json");
        adminClient.post()
                .uri(wireMockUrl() + "/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapping)
                .retrieve()
                .toBodilessEntity();
    }

    private int requestCount(String path, String collectionId) {
        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("method", "POST");
        pattern.put("urlPath", path);
        pattern.putArray("bodyPatterns")
                .addObject()
                .put("contains", collectionId);
        JsonNode result = adminClient.post()
                .uri(wireMockUrl() + "/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(pattern)
                .retrieve()
                .body(JsonNode.class);
        return result == null ? 0 : result.path("count").asInt();
    }

    private int collectionPostCount() {
        return requestCount("/bank-a/collections", "collection_id")
                + requestCount("/cardnet/collections", "collection_id");
    }

    private double cancellationCount(String cause) {
        return meters.get("dunning.cancellations")
                .tag("cause", cause)
                .counter()
                .count();
    }

    private double recoveryCount() {
        return meters.get("dunning.recoveries").counter().count();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String wireMockUrl() {
        return "http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080);
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
                            "/docker-entrypoint-initdb.d/" + paddedMigrationName(path.getFileName().toString())));
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

    private record PaymentState(
            String status, String failureReason, String collectionId) {
    }

    private record SubscriptionState(
            String status, Instant graceUntil, Instant renewedAt) {
    }

    private record PaymentFixture(
            UUID customerId,
            UUID subscriptionId,
            UUID invoiceId,
            UUID chargeId,
            UUID paymentId,
            String collectionId,
            LocalDate dueDate,
            long amountCents,
            String currency,
            String channel,
            String bankId) {
    }
}
