package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "dunning")
public record DunningProperties(Map<String, String> classes,
                                long retriableGraceSeconds,
                                long hardFailGraceSeconds,
                                long disputeGraceSeconds,
                                long retryDelaySeconds,
                                long maxAttempts) {

    public String classFor(String reason) {
        String dunningClass = reason == null ? null : classes.get(reason);
        return dunningClass == null ? "hard_fail" : dunningClass;
    }

    public long graceSecondsFor(String dunningClass) {
        return switch (dunningClass) {
            case "retriable" -> retriableGraceSeconds;
            case "dispute" -> disputeGraceSeconds;
            default -> hardFailGraceSeconds;
        };
    }

    public Set<String> retriableReasons() {
        return classes.entrySet().stream()
                .filter(entry -> "retriable".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
