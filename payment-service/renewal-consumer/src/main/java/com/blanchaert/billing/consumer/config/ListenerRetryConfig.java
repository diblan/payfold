package com.blanchaert.billing.consumer.config;

import com.blanchaert.billing.consumer.service.InvalidRenewalMessageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

@Configuration
public class ListenerRetryConfig {

    @Bean
    public RabbitRetryTemplateCustomizer listenerRetryTemplateCustomizer(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}") int maxAttempts) {
        return (target, retryTemplate) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                // Deterministic contract violations cannot succeed on redelivery, so skip retry.
                retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                        maxAttempts,
                        Map.of(InvalidRenewalMessageException.class, false),
                        true,
                        true));
            }
        };
    }
}
