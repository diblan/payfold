package com.blanchaert.billing.consumer.mq;

import com.blanchaert.billing.consumer.model.RenewalRequested;
import com.blanchaert.billing.consumer.service.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RenewalListener {
    private final ObjectMapper om;
    private final BillingService billing;

    public RenewalListener(ObjectMapper om, BillingService billing) {
        this.om = om;
        this.billing = billing;
    }

    @RabbitListener(id = "renewal", queues = "${rabbitmq.queue}")
    public void onMessage(Message msg) throws Exception {
        RenewalRequested evt = om.readValue(msg.getBody(),
                RenewalRequested.class);
        billing.process(evt);
    }
}
