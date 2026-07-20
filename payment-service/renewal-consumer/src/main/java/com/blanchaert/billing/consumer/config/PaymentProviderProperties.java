package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.provider")
public record PaymentProviderProperties(String baseUrl, int timeoutMs) {
}
