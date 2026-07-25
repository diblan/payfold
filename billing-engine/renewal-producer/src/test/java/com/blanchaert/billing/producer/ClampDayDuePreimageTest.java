package com.blanchaert.billing.producer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.blanchaert.billing.producer.MigratedPostgres.postgresWithMigrations;
import static org.assertj.core.api.Assertions.assertThat;

// The scan marks a subscription due when renewed_at + plan.interval lands on the
// due day. Calendar addition clamps (Jun 30 + 1 month = Jul 30), so on the day
// after a clamp (Jul 31, Dec 31, ...) no monthly renewed_at can be due, and on
// Feb 29 no yearly one can. The seeders therefore choose an interval whose
// subtract-then-add round-trip reproduces the due date — the rule mirrored in
// SubscriptionSeederDueToday, ScanKeysetPaginationTest's seed, and
// scripts/load-test.sh. This test proves the rule is total over every calendar
// day and that Postgres agrees with java.time about the chosen preimage.
@Testcontainers
class ClampDayDuePreimageTest {

    @Container
    static final PostgreSQLContainer<?> postgres = postgresWithMigrations();

    private static String chooseInterval(LocalDateTime dueAt) {
        if (dueAt.minusMonths(1).plusMonths(1).equals(dueAt)) {
            return "month";
        }
        if (dueAt.minusYears(1).plusYears(1).equals(dueAt)) {
            return "year";
        }
        throw new IllegalStateException("no interval round-trips for " + dueAt);
    }

    @Test
    void everyCalendarDayHasARoundTrippingInterval() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2029, 12, 31);
        while (!day.isAfter(end)) {
            chooseInterval(day.atTime(9, 0));
            day = day.plusDays(1);
        }
    }

    @Test
    void yearlyFallbackPlanExists() throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM plan WHERE interval = 'year'");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isGreaterThanOrEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-07-25", "2026-05-31", "2026-07-31", "2026-10-31", "2026-12-31",
            "2027-03-29", "2027-03-30", "2027-03-31", "2028-02-28", "2028-02-29"
    })
    void postgresAgreesWithJavaTimeOnTheChosenPreimage(String isoDate) throws Exception {
        LocalDateTime dueAt = LocalDate.parse(isoDate).atTime(9, 0);
        String interval = chooseInterval(dueAt);
        LocalDateTime renewedAt = "year".equals(interval)
                ? dueAt.minusYears(1)
                : dueAt.minusMonths(1);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ?::timestamp + ('1 ' || ?)::interval")) {
            statement.setString(1, renewedAt.toString().replace('T', ' '));
            statement.setString(2, interval);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getObject(1, LocalDateTime.class)).isEqualTo(dueAt);
            }
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
