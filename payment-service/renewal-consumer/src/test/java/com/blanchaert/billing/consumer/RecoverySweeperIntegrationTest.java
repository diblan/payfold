package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.service.RecoverySweeper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RecoverySweeperIntegrationTest {
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
        registry.add("recovery.stale-after-seconds", () -> "300");
        registry.add("recovery.sweep-interval-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecoverySweeper recoverySweeper;

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
            jdbc.update("DELETE FROM settlement_inbox WHERE bank_id = ? AND notification_id = ?",
                    fixture.bankId(), fixture.collectionId() + ":1");
            jdbc.update("DELETE FROM payment WHERE id = ?", fixture.paymentId());
            jdbc.update("DELETE FROM charge WHERE id = ?", fixture.chargeId());
            jdbc.update("DELETE FROM invoice WHERE id = ?", fixture.invoiceId());
            jdbc.update("DELETE FROM subscription WHERE id = ?", fixture.subscriptionId());
            jdbc.update("DELETE FROM customer WHERE id = ?", fixture.customerId());
        }
        fixtures.clear();
    }

    @Test
    void staleSddPaymentRecoversThroughTheSpine() {
        PaymentFixture fixture = seedSubmitted("SEPA_DD", BANK_A_ID);
        age(fixture);
        stubCollectionStatus(
                collectionPath("/bank-a", fixture),
                sddStatus(fixture, "suppressed"));
        double recoveredBefore = recoveredCount();

        recoverySweeper.sweepOnce();

        assertThat(inboxCount(fixture)).isEqualTo(1L);
        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("succeeded"));
        assertThat(recoveredCount() - recoveredBefore).isEqualTo(1.0);

        recoverySweeper.sweepOnce();

        assertThat(inboxCount(fixture)).isEqualTo(1L);
        assertThat(recoveredCount() - recoveredBefore).isEqualTo(1.0);
    }

    @Test
    void staleCardPaymentRecoversFromNotificationsNotVerdict() {
        PaymentFixture fixture = seedSubmitted("CARD", CARD_ID);
        age(fixture);
        stubCollectionStatus(
                collectionPath("/cardnet", fixture),
                cardStatus(fixture));

        recoverySweeper.sweepOnce();

        awaitDb().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(paymentStatus(fixture)).isEqualTo("succeeded"));
        assertThat(inboxCount(fixture)).isEqualTo(1L);
    }

    @Test
    void freshSubmittedPaymentIsNeverSwept() {
        PaymentFixture fixture = seedSubmitted("SEPA_DD", BANK_A_ID);
        String collectionPath = collectionPath("/bank-a", fixture);
        stubCollectionStatus(collectionPath, sddStatus(fixture, "suppressed"));
        CounterSnapshot before = counters();

        recoverySweeper.sweepOnce();

        assertThat(requestCount("GET", collectionPath, null)).isZero();
        assertThat(paymentStatus(fixture)).isEqualTo("submitted");
        assertThat(counters()).isEqualTo(before);
    }

    @Test
    void bankAmnesiaResubmitsSameCollectionId() {
        PaymentFixture fixture = seedSubmitted("SEPA_DD", BANK_A_ID);
        age(fixture);
        String collectionPath = collectionPath("/bank-a", fixture);
        stubResponse("GET", collectionPath, 404,
                objectMapper.createObjectNode().put("detail", "collection not found"));
        ObjectNode accepted = objectMapper.createObjectNode();
        accepted.put("collection_id", fixture.collectionId());
        accepted.put("status", "accepted");
        accepted.put("duplicate", false);
        stubResponse("POST", "/bank-a/collections", 202, accepted);
        ObjectNode expectedSubmission = objectMapper.createObjectNode();
        expectedSubmission.put("collection_id", fixture.collectionId());
        expectedSubmission.put("amount_cents", fixture.amountCents());
        expectedSubmission.put("currency", fixture.currency());
        expectedSubmission.put("debtor_iban", fixture.debtorIban());
        expectedSubmission.put("mandate_reference", fixture.mandateReference());
        expectedSubmission.put("due_date", fixture.dueDate().toString());
        double resubmittedBefore = sweepCount("resubmitted");
        double recoveredBefore = recoveredCount();

        recoverySweeper.sweepOnce();

        assertThat(requestCount("POST", "/bank-a/collections", expectedSubmission))
                .isEqualTo(1);
        assertThat(paymentStatus(fixture)).isEqualTo("submitted");
        assertThat(sweepCount("resubmitted") - resubmittedBefore).isEqualTo(1.0);
        assertThat(recoveredCount() - recoveredBefore).isZero();
    }

    @Test
    void lateWebhookWinsTheRaceSweepIsNoop() throws Exception {
        PaymentFixture fixture = seedSubmitted("SEPA_DD", BANK_A_ID);
        age(fixture);
        ObjectNode payload = settlementPayload(fixture);
        jdbc.update("""
                INSERT INTO settlement_inbox (
                    bank_id, notification_id, payload, published_at
                ) VALUES (?, ?, ?::jsonb, now())
                """, fixture.bankId(), fixture.collectionId() + ":1",
                objectMapper.writeValueAsString(payload));
        stubCollectionStatus(
                collectionPath("/bank-a", fixture),
                sddStatus(fixture, "delivered"));
        double recoveredBefore = recoveredCount();
        double noopBefore = sweepCount("noop");

        recoverySweeper.sweepOnce();

        assertThat(recoveredCount() - recoveredBefore).isZero();
        assertThat(sweepCount("noop") - noopBefore).isGreaterThanOrEqualTo(1.0);
        assertThat(inboxCount(fixture)).isEqualTo(1L);
    }

    private PaymentFixture seedSubmitted(String channel, String bankId) {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID chargeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID planId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'", UUID.class);
        LocalDate dueDate = LocalDate.of(2032, 8, 1);
        String collectionId = "recovery-" + paymentId + "|" + dueDate;
        long amountCents = 1499L;
        String currency = "EUR";
        String debtorIban = "SEPA_DD".equals(channel) ? "BE6800000000000094" : null;
        String mandateReference = "SEPA_DD".equals(channel) ? "MNDT-RECOVERY" : null;
        String cardToken = "CARD".equals(channel) ? "tok-0000000094" : null;
        String paymentMethod = "CARD".equals(channel) ? "card" : "sdd";

        jdbc.update("""
                INSERT INTO customer (
                    id, email, name, status, payment_method, debtor_iban,
                    mandate_reference, country, card_token
                ) VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?)
                """, customerId, "recovery-" + customerId + "@example.com",
                "Recovery Test Customer", paymentMethod, debtorIban,
                mandateReference, "SEPA_DD".equals(channel) ? "BE" : null, cardToken);
        jdbc.update("""
                INSERT INTO subscription (
                    id, customer_id, plan_id, status, renewed_at
                ) VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId,
                dueDate.minusMonths(1).atTime(9, 0).atOffset(ZoneOffset.UTC));
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
                ) VALUES (?, ?, ?, ?, ?, 'Recovery test charge', 'pending', ?)
                """, chargeId, subscriptionId, invoiceId, amountCents, currency, dueDate);
        jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, requested_at, bank_id, collection_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'submitted', now(), ?, ?)
                """, paymentId, chargeId, amountCents, currency, channel,
                collectionId, bankId, collectionId);

        PaymentFixture fixture = new PaymentFixture(
                customerId, subscriptionId, invoiceId, chargeId, paymentId,
                collectionId, bankId, amountCents, currency, dueDate,
                debtorIban, mandateReference, cardToken);
        fixtures.add(fixture);
        return fixture;
    }

    private void age(PaymentFixture fixture) {
        jdbc.update("""
                UPDATE payment
                SET requested_at = now() - interval '10 minutes'
                WHERE id = ?
                """, fixture.paymentId());
    }

    private JsonNode sddStatus(PaymentFixture fixture, String state) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("collection_id", fixture.collectionId());
        status.put("amount_cents", fixture.amountCents());
        status.put("currency", fixture.currency());
        status.put("debtor_iban", fixture.debtorIban());
        status.put("mandate_reference", fixture.mandateReference());
        status.put("due_date", fixture.dueDate().toString());
        status.put("outcome", "settled");
        status.putNull("reason");
        status.put("response_status", "accepted");
        status.putNull("response_reason");
        status.set("notifications", notification(fixture, state));
        return status;
    }

    private JsonNode cardStatus(PaymentFixture fixture) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("collection_id", fixture.collectionId());
        status.put("amount_cents", fixture.amountCents());
        status.put("currency", fixture.currency());
        status.put("card_token", fixture.cardToken());
        status.put("due_date", fixture.dueDate().toString());
        status.put("outcome", "authorized");
        status.putNull("reason");
        status.put("response_status", "authorized");
        status.putNull("response_reason");
        status.set("notifications", notification(fixture, "suppressed"));
        return status;
    }

    private JsonNode notification(PaymentFixture fixture, String state) {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("seq", 1);
        notification.put("outcome", "settled");
        notification.putNull("reason");
        notification.put("notification_id", fixture.collectionId() + ":1");
        notification.put("state", state);
        return objectMapper.createArrayNode().add(notification);
    }

    private ObjectNode settlementPayload(PaymentFixture fixture) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", 1);
        payload.put("bank_id", fixture.bankId());
        payload.put("notification_id", fixture.collectionId() + ":1");
        payload.put("collection_id", fixture.collectionId());
        payload.put("outcome", "settled");
        payload.putNull("reason");
        payload.put("occurred_at", "2032-08-01T00:00:00Z");
        return payload;
    }

    private void stubCollectionStatus(String path, JsonNode body) {
        stubResponse("GET", path, 200, body);
    }

    private void stubResponse(String method, String path, int status, JsonNode body) {
        ObjectNode mapping = objectMapper.createObjectNode();
        ObjectNode request = mapping.putObject("request");
        request.put("method", method);
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

    private int requestCount(String method, String path, JsonNode expectedBody) {
        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("method", method);
        pattern.put("urlPath", path);
        if (expectedBody != null) {
            ObjectNode bodyPattern = objectMapper.createObjectNode();
            bodyPattern.put("equalToJson", expectedBody.toString());
            bodyPattern.put("ignoreArrayOrder", true);
            bodyPattern.put("ignoreExtraElements", false);
            pattern.putArray("bodyPatterns").add(bodyPattern);
        }
        JsonNode result = adminClient.post()
                .uri(wireMockUrl() + "/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(pattern)
                .retrieve()
                .body(JsonNode.class);
        return result == null ? 0 : result.path("count").asInt();
    }

    private long inboxCount(PaymentFixture fixture) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM settlement_inbox
                WHERE bank_id = ? AND notification_id = ?
                """, Long.class, fixture.bankId(), fixture.collectionId() + ":1");
    }

    private String paymentStatus(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?",
                String.class, fixture.paymentId());
    }

    private double recoveredCount() {
        return meters.get("settlements.recovered").counter().count();
    }

    private double sweepCount(String result) {
        return meters.get("recovery.sweeps").tag("result", result).counter().count();
    }

    private CounterSnapshot counters() {
        return new CounterSnapshot(
                recoveredCount(), sweepCount("recovered"),
                sweepCount("resubmitted"), sweepCount("noop"));
    }

    private static ConditionFactory awaitDb() {
        return await().ignoreExceptionsInstanceOf(EmptyResultDataAccessException.class);
    }

    // WireMock matches on the encoded request path, and the collection id's
    // '|' arrives percent-encoded — stubs must carry the same encoding the
    // client applies.
    private static String collectionPath(String bankPrefix, PaymentFixture fixture) {
        return bankPrefix + "/collections/"
                + UriUtils.encodePathSegment(fixture.collectionId(), StandardCharsets.UTF_8);
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

    private record CounterSnapshot(
            double recovered, double recoveredSweeps,
            double resubmittedSweeps, double noopSweeps) {
    }

    private record PaymentFixture(
            UUID customerId,
            UUID subscriptionId,
            UUID invoiceId,
            UUID chargeId,
            UUID paymentId,
            String collectionId,
            String bankId,
            long amountCents,
            String currency,
            LocalDate dueDate,
            String debtorIban,
            String mandateReference,
            String cardToken) {
    }
}
