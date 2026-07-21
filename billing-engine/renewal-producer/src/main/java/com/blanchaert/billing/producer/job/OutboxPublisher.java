package com.blanchaert.billing.producer.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final RabbitTemplate rabbit;
    private final String exchange;
    private final String routingKey;
    private final Counter returnedCounter;

    public OutboxPublisher(RabbitTemplate rabbit,
                           MeterRegistry meters,
                           @Value("${rabbitmq.exchange}") String exchange,
                           @Value("${rabbitmq.routingKey}") String routingKey) {
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.returnedCounter = Counter.builder("outbox.returned")
                .description("Outbox rows whose message the broker returned as unroutable")
                .register(meters);
        // Return handling is correlation-based in publish(); this callback only
        // exists because RabbitTemplate logs one generic WARN per returned
        // message when no callback is registered.
        rabbit.setReturnsCallback(returned -> log.debug("Publisher return delivered: {}", returned));
    }

    public CompletableFuture<Boolean> publish(String id, String json) {
        Message msg = MessageBuilder.withBody(json.getBytes()).setContentType("application/json").build();
        CorrelationData correlation = new CorrelationData(id);
        rabbit.convertAndSend(exchange, routingKey, msg, correlation);
        return correlation.getFuture().thenApply(confirm -> {
            // The broker acks a mandatory unroutable message right after
            // returning it, so the return must win over the ack: spring-rabbit
            // populates the correlation's returned message before completing
            // this future, making this check race-free.
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                returnedCounter.increment();
                log.warn("Outbox row {} returned unroutable (exchange={}, routingKey={}, replyCode={}, replyText={}); row stays unpublished",
                        id, returned.getExchange(), returned.getRoutingKey(),
                        returned.getReplyCode(), returned.getReplyText());
                return false;
            }
            return confirm != null && confirm.isAck();
        });
    }
}
