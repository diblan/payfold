package com.blanchaert.billing.consumer.service;

import com.blanchaert.billing.consumer.config.DunningProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DunningLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DunningLifecycle.class);

    private final JdbcTemplate jdbc;
    private final DunningProperties properties;
    private final Map<String, Counter> transitions;
    private final AtomicLong pastDueDepth = new AtomicLong();

    public DunningLifecycle(
            JdbcTemplate jdbc, DunningProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.properties = properties;
        Map<String, Counter> counters = new HashMap<>();
        for (String dunningClass : new String[]{"retriable", "hard_fail", "dispute"}) {
            counters.put(dunningClass, Counter.builder("dunning.transitions")
                    .description("Subscriptions entering past_due grace by dunning class")
                    .tag("class", dunningClass)
                    .register(meters));
        }
        this.transitions = Map.copyOf(counters);
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

    @Scheduled(fixedDelay = 10_000)
    public void refreshPastDueDepth() {
        pastDueDepth.set(jdbc.queryForObject(
                "SELECT count(*) FROM subscription WHERE status = 'past_due'",
                Long.class));
    }
}
