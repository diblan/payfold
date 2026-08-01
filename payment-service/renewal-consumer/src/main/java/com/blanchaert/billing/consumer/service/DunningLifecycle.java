package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.config.DunningProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DunningLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DunningLifecycle.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final DunningProperties properties;
    private final Map<String, Counter> transitions;
    private final Map<String, Counter> cancellations;
    private final Counter recoveries;
    private final AtomicLong pastDueDepth = new AtomicLong();

    public DunningLifecycle(
            JdbcTemplate jdbc, DunningProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbc.getDataSource()));
        this.properties = properties;
        Map<String, Counter> counters = new HashMap<>();
        for (String dunningClass : new String[]{"retriable", "hard_fail", "dispute"}) {
            counters.put(dunningClass, Counter.builder("dunning.transitions")
                    .description("Subscriptions entering past_due grace by dunning class")
                    .tag("class", dunningClass)
                    .register(meters));
        }
        this.transitions = Map.copyOf(counters);
        Map<String, Counter> cancelCounters = new HashMap<>();
        for (String cause : new String[]{"exhausted", "grace_expired"}) {
            cancelCounters.put(cause, Counter.builder("dunning.cancellations")
                    .description("Subscriptions canceled by dunning enforcement")
                    .tag("cause", cause)
                    .register(meters));
        }
        this.cancellations = Map.copyOf(cancelCounters);
        this.recoveries = Counter.builder("dunning.recoveries")
                .description("Subscriptions recovering from past_due grace")
                .register(meters);
        Gauge.builder("subscriptions.past_due", pastDueDepth, AtomicLong::get)
                .description("Subscriptions currently in past_due grace")
                .register(meters);
    }

    public void enterGrace(UUID subscriptionId, String reason) {
        String dunningClass = properties.classFor(reason);
        int updated = jdbc.update("""
                UPDATE subscription
                SET status = 'past_due', grace_until = now() + (? * interval '1 second')
                WHERE id = ? AND status = 'active'
                """, properties.graceSecondsFor(dunningClass), subscriptionId);
        if (updated == 1) {
            transitions.get(dunningClass).increment();
            log.info("Subscription {} entered past_due grace (class={}, reason={})",
                    subscriptionId, dunningClass, reason);
        }
    }

    public void recoverFromGrace(UUID subscriptionId) {
        // A grace deadline outside past_due is stale data that R26c's expiry
        // sweep must never see, so recovery clears it atomically (D20).
        int updated = jdbc.update("""
                UPDATE subscription
                SET status = 'active', grace_until = NULL
                WHERE id = ? AND status = 'past_due'
                """, subscriptionId);
        if (updated == 1) {
            recoveries.increment();
            log.info("Subscription {} recovered from past_due grace", subscriptionId);
        }
    }

    public void cancelExhausted(Set<String> retriableReasons, long maxAttempts) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("reasons", retriableReasons)
                .addValue("maxAttempts", maxAttempts);
        int canceled = namedJdbc.update("""
            UPDATE subscription AS s
            SET status = 'canceled', grace_until = NULL
            WHERE s.status = 'past_due'
              AND EXISTS (
                SELECT 1 FROM charge ch
                JOIN payment p ON p.charge_id = ch.id
                WHERE ch.subscription_id = s.id
                  AND p.status = 'failed'
                  AND p.failure_reason IN (:reasons)
                  AND p.attempt >= :maxAttempts
                  AND p.attempt = (SELECT max(p2.attempt) FROM payment p2
                                   WHERE p2.charge_id = ch.id)
              )
            """, parameters);
        recordCancellations("exhausted", canceled);
    }

    public void cancelExpired() {
        int canceled = jdbc.update("""
            UPDATE subscription
            SET status = 'canceled', grace_until = NULL
            WHERE status = 'past_due' AND grace_until < now()
            """);
        recordCancellations("grace_expired", canceled);
    }

    private void recordCancellations(String cause, int count) {
        // Canceled rows have left past_due, so both passes are idempotent by
        // predicate; log per sweep with a count, never per row.
        if (count > 0) {
            cancellations.get(cause).increment(count);
            log.info("Canceled {} subscription(s) (cause={})", count, cause);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void refreshPastDueDepth() {
        pastDueDepth.set(jdbc.queryForObject(
                "SELECT count(*) FROM subscription WHERE status = 'past_due'",
                Long.class));
    }
}
