package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(String id, String baseUrl, int timeoutMs) {
}
