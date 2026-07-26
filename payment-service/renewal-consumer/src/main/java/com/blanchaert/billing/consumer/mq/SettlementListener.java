package com.blanchaert.billing.consumer.mq;

import com.blanchaert.billing.consumer.config.SettlementTopology;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.blanchaert.billing.consumer.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SettlementListener {
    private final ObjectMapper objectMapper;
    private final SettlementService settlementService;

    public SettlementListener(ObjectMapper objectMapper, SettlementService settlementService) {
        this.objectMapper = objectMapper;
        this.settlementService = settlementService;
    }

    @RabbitListener(id = "settlement", queues = SettlementTopology.MAIN_QUEUE)
    public void onMessage(Message message) throws Exception {
        SettlementReceived event = objectMapper.readValue(
                message.getBody(), SettlementReceived.class);
        settlementService.apply(event);
    }
}
