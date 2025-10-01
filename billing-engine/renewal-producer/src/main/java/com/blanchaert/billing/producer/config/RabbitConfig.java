package com.blanchaert.billing.producer.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    public Exchange renewalsExchange(@Value("${rabbitmq.exchange}") String ex) {
        return ExchangeBuilder.directExchange(ex).durable(true).build();
    }
}
