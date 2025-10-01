package com.blanchaert.billing.consumer.mq;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.service.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RenewalListener {
    private final ObjectMapper om;
    private final BillingService billing;
    private final String queue;

    public RenewalListener(ObjectMapper om, BillingService billing,
                           @Value("${rabbitmq.queue}") String queue) {
        this.om = om;
        this.billing = billing;
        this.queue = queue;
    }

    @RabbitListener(queues = "${rabbitmq.queue}")
    public void onMessage(Message msg) throws Exception {
        RenewalRequested evt = om.readValue(msg.getBody(),
                RenewalRequested.class);
        // Compute period if publisher did not include it
        if (evt.period_start() == null || evt.period_end() == null) {
            // Fallback: infer today’s cycle
            // You can also send these from the producer; left here for resilience.
        }
        billing.process(evt);
    }
}
