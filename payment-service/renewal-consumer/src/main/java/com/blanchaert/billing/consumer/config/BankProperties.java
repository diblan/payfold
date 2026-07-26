package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(int timeoutMs, List<BankEntry> registry) {
    public record BankEntry(String id, String baseUrl, String webhookSecret,
                            List<String> countries) {
    }
}
