package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "dunning")
public record DunningProperties(Map<String, String> classes,
                                long retriableGraceSeconds,
                                long hardFailGraceSeconds,
                                long disputeGraceSeconds) {

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
}
