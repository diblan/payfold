package com.blanchaert.billing.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recovery")
public record RecoveryProperties(long staleAfterSeconds) {
}
