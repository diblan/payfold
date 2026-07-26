package com.blanchaert.billing.consumer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SettlementTopology {
    public static final String EXCHANGE = "billing.settlements";
    public static final String ROUTING_KEY = "settlement.received";
    public static final String MAIN_QUEUE = "billing.settlements.main";
    public static final String DLX = "billing.settlements.dlx";
    public static final String DLQ_ROUTING_KEY = "dlq";
    public static final String DLQ = "billing.settlements.dlq";

    @Bean
    public DirectExchange settlementsExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue settlementsMainQueue() {
        return QueueBuilder.durable(MAIN_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding settlementsMainBinding(
            @Qualifier("settlementsMainQueue") Queue settlementsMainQueue,
            @Qualifier("settlementsExchange") DirectExchange settlementsExchange) {
        return BindingBuilder.bind(settlementsMainQueue)
                .to(settlementsExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange settlementsDlx() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue settlementsDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding settlementsDlqBinding(
            @Qualifier("settlementsDlq") Queue settlementsDlq,
            @Qualifier("settlementsDlx") DirectExchange settlementsDlx) {
        return BindingBuilder.bind(settlementsDlq)
                .to(settlementsDlx)
                .with(DLQ_ROUTING_KEY);
    }
}
