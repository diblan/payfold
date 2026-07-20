package com.blanchaert.billing.producer.job;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class OutboxPublisher {
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final String routingKey;

    public OutboxPublisher(RabbitTemplate rabbit,
                           @Value("${rabbitmq.exchange}") String exchange,
                           @Value("${rabbitmq.routingKey}") String routingKey) {
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public CompletableFuture<Boolean> publish(String id, String json) {
        Message msg = MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
        CorrelationData correlation = new CorrelationData(id);
        rabbit.convertAndSend(exchange, routingKey, msg, correlation);
        return correlation.getFuture().thenApply(confirm -> confirm != null && confirm.isAck());
    }
}
