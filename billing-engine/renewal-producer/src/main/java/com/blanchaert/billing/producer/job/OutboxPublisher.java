package com.blanchaert.billing.producer.job;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public boolean publish(String json) {
        Message msg = MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
        rabbit.convertAndSend(exchange, routingKey, msg);
        return true;
    }
}
