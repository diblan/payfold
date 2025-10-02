package com.blanchaert.billing.consumer.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
    @Bean
    public Exchange renewalsExchange(@Value("${rabbitmq.exchange}") String ex) {
        return ExchangeBuilder.directExchange(ex).durable(true).build();
    }

    @Bean
    public Queue mainQueue(@Value("${rabbitmq.queue}") String q) {
        return QueueBuilder.durable(q).withArgument("x-dead-letter-exchange", "billing.renewals.dlx").build();
    }

    @Bean
    public Binding mainBinding(@Qualifier("mainQueue") Queue mainQueue, Exchange renewalsExchange, @Value("${rabbitmq.routingKey}") String rk) {
        return BindingBuilder.bind(mainQueue).to(renewalsExchange).with(rk).noargs();
    }

    @Bean
    public Exchange dlx() {
        return ExchangeBuilder.directExchange("billing.renewals.dlx").durable(true).build();
    }

    @Bean
    public Queue dlq() {
        return QueueBuilder.durable("billing.renewals.dlq").build();
    }

    @Bean
    public Binding dlqBinding(Queue dlq, Exchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with("dlq").noargs();
    }
}