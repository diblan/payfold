package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(int timeoutMs, Integer webhookToleranceSeconds, List<BankEntry> registry) {
    // R44: how far a webhook's signed X-Bank-Timestamp may sit from the
    // receiver's clock, either direction. Defaults when the key is absent (the
    // integration suites bind only the registry).
    public static final int DEFAULT_WEBHOOK_TOLERANCE_SECONDS = 300;

    public BankProperties {
        if (webhookToleranceSeconds == null) {
            webhookToleranceSeconds = DEFAULT_WEBHOOK_TOLERANCE_SECONDS;
        }
        if (webhookToleranceSeconds < 0) {
            throw new IllegalArgumentException(
                    "bank.webhook-tolerance-seconds must be >= 0, got " + webhookToleranceSeconds);
        }
    }

    public record BankEntry(String id, String scheme, String baseUrl, String webhookSecret,
                            List<String> countries) {
    }
}
