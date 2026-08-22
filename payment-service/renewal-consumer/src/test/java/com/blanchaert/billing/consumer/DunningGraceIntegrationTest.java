package com.blanchaert.billing.consumer;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.blanchaert.billing.consumer.service.BillingService;
import com.blanchaert.billing.consumer.service.DunningLifecycle;
import com.blanchaert.billing.consumer.service.SettlementService;
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
class DunningGraceIntegrationTest {
    private static final String BANK_A_ID = "bank-a";
    private static final String CARD_ID = "cardnet";
    private static final long RETRIABLE_GRACE_SECONDS = 600L;
    private static final long HARD_FAIL_GRACE_SECONDS = 300L;
    private static final long DISPUTE_GRACE_SECONDS = 900L;

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
        registry.add("dunning.retriable-grace-seconds",
                () -> Long.toString(RETRIABLE_GRACE_SECONDS));
        registry.add("dunning.hard-fail-grace-seconds",
                () -> Long.toString(HARD_FAIL_GRACE_SECONDS));
        registry.add("dunning.dispute-grace-seconds",
                () -> Long.toString(DISPUTE_GRACE_SECONDS));
    }

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private BillingService billingService;

    @Autowired
    private DunningLifecycle dunningLifecycle;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

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
            jdbc.update("DELETE FROM payment WHERE id = ?", fixture.paymentId());
            jdbc.update("DELETE FROM charge WHERE id = ?", fixture.chargeId());
            jdbc.update("DELETE FROM invoice WHERE id = ?", fixture.invoiceId());
            jdbc.update("DELETE FROM subscription WHERE id = ?", fixture.subscriptionId());
            jdbc.update("DELETE FROM customer WHERE id = ?", fixture.customerId());
        }
        fixtures.clear();
    }

    @Test
    void failedSettlementEntersRetriableGrace() {
        PaymentFixture fixture = seedPayment("sdd", "SEPA_DD", "submitted", false);
        SettlementReceived event = settlement(fixture, "failed", "AM04");
        double transitionsBefore = transitionCount("retriable");
        Instant beforeCall = Instant.now();

        settlementService.apply(event);

        Instant afterCall = Instant.now();
        SubscriptionState afterFirst = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("failed");
        assertThat(afterFirst.status()).isEqualTo("past_due");
        assertGraceWindow(afterFirst.graceUntil(), beforeCall, afterCall,
                RETRIABLE_GRACE_SECONDS);
        assertThat(transitionCount("retriable") - transitionsBefore).isEqualTo(1.0);

        settlementService.apply(event);

        SubscriptionState afterRedelivery = subscriptionState(fixture);
        assertThat(afterRedelivery.status()).isEqualTo("past_due");
        assertThat(afterRedelivery.graceUntil()).isEqualTo(afterFirst.graceUntil());
        assertThat(transitionCount("retriable") - transitionsBefore).isEqualTo(1.0);
    }

    @Test
    void chargebackAfterSettlementEntersDisputeGraceKeepingAdvance() {
        PaymentFixture fixture = seedPayment("sdd", "SEPA_DD", "succeeded", true);
        SettlementReceived event = settlement(fixture, "charged_back", "MD06");
        Instant renewedBefore = subscriptionState(fixture).renewedAt();
        double transitionsBefore = transitionCount("dispute");
        Instant beforeCall = Instant.now();

        settlementService.apply(event);

        Instant afterCall = Instant.now();
        SubscriptionState state = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
        assertThat(state.status()).isEqualTo("past_due");
        assertThat(state.renewedAt()).isEqualTo(renewedBefore);
        assertThat(invoiceStatus(fixture)).isEqualTo("disputed");
        assertGraceWindow(state.graceUntil(), beforeCall, afterCall,
                DISPUTE_GRACE_SECONDS);
        assertThat(transitionCount("dispute") - transitionsBefore).isEqualTo(1.0);

        settlementService.apply(event);

        SubscriptionState afterRedelivery = subscriptionState(fixture);
        assertThat(afterRedelivery.graceUntil()).isEqualTo(state.graceUntil());
        assertThat(afterRedelivery.renewedAt()).isEqualTo(renewedBefore);
        assertThat(transitionCount("dispute") - transitionsBefore).isEqualTo(1.0);
    }

    @Test
    void chargebackBeforeSettlementConvergesToTheOrderedTerminalState() {
        PaymentFixture fixture = seedPayment("sdd", "SEPA_DD", "submitted", false);
        double transitionsBefore = transitionCount("dispute");
        Instant beforeCall = Instant.now();

        settlementService.apply(settlement(fixture, "charged_back", "MD06"));

        // The chargeback presupposes the collection settled, so its arrival
        // carries the settle's effects: either order must end with the charge
        // settled, the period advanced, and the invoice disputed (R41).
        Instant afterCall = Instant.now();
        SubscriptionState state = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
        assertThat(state.status()).isEqualTo("past_due");
        assertThat(invoiceStatus(fixture)).isEqualTo("disputed");
        assertThat(chargeStatus(fixture)).isEqualTo("settled");
        assertThat(state.renewedAt()).isEqualTo(expectedAdvance(fixture));
        assertGraceWindow(state.graceUntil(), beforeCall, afterCall,
                DISPUTE_GRACE_SECONDS);
        assertThat(transitionCount("dispute") - transitionsBefore).isEqualTo(1.0);

        settlementService.apply(settlement(fixture, "settled", null));

        SubscriptionState afterLateSettled = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("charged_back");
        assertThat(invoiceStatus(fixture)).isEqualTo("disputed");
        assertThat(afterLateSettled.status()).isEqualTo("past_due");
        assertThat(afterLateSettled.renewedAt()).isEqualTo(state.renewedAt());
        assertThat(afterLateSettled.graceUntil()).isEqualTo(state.graceUntil());

        settlementService.apply(settlement(fixture, "charged_back", "MD06"));

        SubscriptionState afterRedelivery = subscriptionState(fixture);
        assertThat(afterRedelivery.renewedAt()).isEqualTo(state.renewedAt());
        assertThat(afterRedelivery.graceUntil()).isEqualTo(state.graceUntil());
        assertThat(transitionCount("dispute") - transitionsBefore).isEqualTo(1.0);
    }

    @Test
    void cardDeclineEntersHardFailGrace() {
        PaymentFixture fixture = seedPayment("card", "CARD", "pending", false);
        stubCardDecline("do_not_honor");
        RenewalRequested event = renewal(fixture);
        double transitionsBefore = transitionCount("hard_fail");
        Instant beforeCall = Instant.now();

        billingService.process(event);

        Instant afterCall = Instant.now();
        SubscriptionState afterFirst = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("failed");
        assertThat(paymentFailureReason(fixture)).isEqualTo("do_not_honor");
        assertThat(afterFirst.status()).isEqualTo("past_due");
        assertGraceWindow(afterFirst.graceUntil(), beforeCall, afterCall,
                HARD_FAIL_GRACE_SECONDS);
        assertThat(transitionCount("hard_fail") - transitionsBefore).isEqualTo(1.0);

        billingService.process(event);

        SubscriptionState afterRedelivery = subscriptionState(fixture);
        assertThat(afterRedelivery.graceUntil()).isEqualTo(afterFirst.graceUntil());
        assertThat(transitionCount("hard_fail") - transitionsBefore).isEqualTo(1.0);
    }

    @Test
    void settledPaymentLeavesSubscriptionActive() {
        PaymentFixture fixture = seedPayment("sdd", "SEPA_DD", "submitted", false);
        CounterSnapshot before = counterSnapshot();

        settlementService.apply(settlement(fixture, "settled", null));

        SubscriptionState state = subscriptionState(fixture);
        assertThat(paymentStatus(fixture)).isEqualTo("succeeded");
        assertThat(state.status()).isEqualTo("active");
        assertThat(state.graceUntil()).isNull();
        assertThat(counterSnapshot()).isEqualTo(before);

        dunningLifecycle.refreshPastDueDepth();
    }

    private PaymentFixture seedPayment(
            String paymentMethod, String channel, String paymentStatus, boolean advanced) {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID chargeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID planId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE name = 'Standard'", UUID.class);
        LocalDate dueDate = LocalDate.of(2033, 2, 1);
        LocalDate periodEnd = dueDate.plusMonths(1);
        String collectionId = "dunning-" + paymentId + "|" + dueDate;
        long amountCents = 1499L;
        String currency = "EUR";
        boolean card = "card".equals(paymentMethod);
        String debtorIban = card ? null : "BE6800000000000099";
        String mandateReference = card ? null : "MNDT-DUNNING";
        String cardToken = card ? "tok-0000000098" : null;
        Instant renewedAt = (advanced ? periodEnd : dueDate.minusMonths(1))
                .atTime(9, 0).toInstant(ZoneOffset.UTC);
        boolean attributed = !"pending".equals(paymentStatus);

        jdbc.update("""
                INSERT INTO customer (
                    id, email, name, status, payment_method, debtor_iban,
                    mandate_reference, country, card_token
                ) VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?)
                """, customerId, "dunning-" + customerId + "@example.com",
                "Dunning Test Customer", paymentMethod, debtorIban,
                mandateReference, card ? null : "BE", cardToken);
        jdbc.update("""
                INSERT INTO subscription (
                    id, customer_id, plan_id, status, renewed_at
                ) VALUES (?, ?, ?, 'active', ?)
                """, subscriptionId, customerId, planId, Timestamp.from(renewedAt));
        jdbc.update("""
                INSERT INTO invoice (
                    id, customer_id, period_start, period_end, total_cents,
                    currency, number, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, invoiceId, customerId, dueDate, periodEnd,
                amountCents, currency, "INV-" + invoiceId,
                "succeeded".equals(paymentStatus) ? "paid" : "posted");
        jdbc.update("""
                INSERT INTO charge (
                    id, subscription_id, invoice_id, amount_cents, currency,
                    description, status, due_date
                ) VALUES (?, ?, ?, ?, ?, 'Dunning test charge', ?, ?)
                """, chargeId, subscriptionId, invoiceId, amountCents, currency,
                "succeeded".equals(paymentStatus) ? "settled" : "pending", dueDate);
        jdbc.update("""
                INSERT INTO payment (
                    id, charge_id, amount_cents, currency, channel,
                    idempotency_key, status, requested_at, completed_at,
                    bank_id, collection_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
                """, paymentId, chargeId, amountCents, currency, channel,
                collectionId, paymentStatus,
                "succeeded".equals(paymentStatus) ? Timestamp.from(Instant.now()) : null,
                attributed ? (card ? CARD_ID : BANK_A_ID) : null,
                attributed ? collectionId : null);

        PaymentFixture fixture = new PaymentFixture(
                customerId, subscriptionId, invoiceId, chargeId, paymentId, planId,
                collectionId, dueDate, periodEnd, amountCents, currency);
        fixtures.add(fixture);
        return fixture;
    }

    private SettlementReceived settlement(
            PaymentFixture fixture, String outcome, String reason) {
        return new SettlementReceived(
                1, fixture.collectionId() + ":notification", BANK_A_ID,
                fixture.collectionId(), outcome, reason, Instant.now().toString());
    }

    private RenewalRequested renewal(PaymentFixture fixture) {
        return new RenewalRequested(
                1, UUID.randomUUID(), fixture.subscriptionId(), fixture.customerId(),
                fixture.planId(), "month", fixture.amountCents(), fixture.currency(),
                fixture.collectionId(), fixture.dueDate().toString(),
                fixture.dueDate().toString(), fixture.periodEnd().toString(),
                Instant.now().toString());
    }

    private void stubCardDecline(String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", "declined");
        body.put("reason", reason);
        ObjectNode mapping = objectMapper.createObjectNode();
        ObjectNode request = mapping.putObject("request");
        request.put("method", "POST");
        request.put("urlPath", "/cardnet/collections");
        ObjectNode response = mapping.putObject("response");
        response.put("status", 200);
        response.set("jsonBody", body);
        response.putObject("headers").put("Content-Type", "application/json");
        adminClient.post()
                .uri(wireMockUrl() + "/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapping)
                .retrieve()
                .toBodilessEntity();
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

    private String paymentStatus(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?",
                String.class, fixture.paymentId());
    }

    private String paymentFailureReason(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM payment WHERE id = ?",
                String.class, fixture.paymentId());
    }

    private String invoiceStatus(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM invoice WHERE id = ?",
                String.class, fixture.invoiceId());
    }

    private String chargeStatus(PaymentFixture fixture) {
        return jdbc.queryForObject(
                "SELECT status FROM charge WHERE id = ?",
                String.class, fixture.chargeId());
    }

    // Mirrors the settle advance: Timestamp.valueOf renders period_end 09:00
    // in the JVM zone, exactly as SettlementService writes it.
    private Instant expectedAdvance(PaymentFixture fixture) {
        return Timestamp.valueOf(fixture.periodEnd().atTime(9, 0)).toInstant();
    }

    private double transitionCount(String dunningClass) {
        return meters.get("dunning.transitions")
                .tag("class", dunningClass)
                .counter()
                .count();
    }

    private CounterSnapshot counterSnapshot() {
        return new CounterSnapshot(
                transitionCount("retriable"), transitionCount("hard_fail"),
                transitionCount("dispute"));
    }

    private void assertGraceWindow(
            Instant graceUntil, Instant beforeCall, Instant afterCall, long graceSeconds) {
        assertThat(graceUntil).isBetween(
                beforeCall.plusSeconds(graceSeconds),
                afterCall.plusSeconds(graceSeconds + 2));
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

    private record SubscriptionState(String status, Instant graceUntil, Instant renewedAt) {
    }

    private record CounterSnapshot(double retriable, double hardFail, double dispute) {
    }

    private record PaymentFixture(
            UUID customerId,
            UUID subscriptionId,
            UUID invoiceId,
            UUID chargeId,
            UUID paymentId,
            UUID planId,
            String collectionId,
            LocalDate dueDate,
            LocalDate periodEnd,
            long amountCents,
            String currency) {
    }
}
