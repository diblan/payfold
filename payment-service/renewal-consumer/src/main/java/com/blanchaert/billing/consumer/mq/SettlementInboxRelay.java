package com.blanchaert.billing.consumer.mq;

import com.blanchaert.billing.consumer.config.SettlementTopology;
import com.blanchaert.billing.consumer.model.SettlementReceived;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class SettlementInboxRelay {
    private static final Logger log = LoggerFactory.getLogger(SettlementInboxRelay.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public SettlementInboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relayOnce() {
        List<InboxRow> rows = jdbc.query("""
                        SELECT id, bank_id, notification_id, payload
                        FROM settlement_inbox
                        WHERE published_at IS NULL
                        ORDER BY received_at
                        LIMIT 100
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> new InboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("bank_id"),
                        rs.getString("notification_id"),
                        rs.getString("payload")));

        for (InboxRow row : rows) {
            try {
                SettlementReceived event = normalize(row);
                Message message = MessageBuilder
                        .withBody(objectMapper.writeValueAsBytes(event))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build();
                CorrelationData correlationData = new CorrelationData();
                rabbitTemplate.convertAndSend(
                        SettlementTopology.EXCHANGE,
                        SettlementTopology.ROUTING_KEY,
                        message,
                        correlationData);
                CorrelationData.Confirm confirm =
                        correlationData.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    log.warn("Settlement relay publish nacked for inbox row {}: {}",
                            row.id(), confirm.getReason());
                    return;
                }
                jdbc.update("""
                        UPDATE settlement_inbox
                        SET published_at = now()
                        WHERE id = ?
                        """, row.id());
            } catch (Exception exception) {
                log.warn("Settlement relay stopped at inbox row {}", row.id(), exception);
                return;
            }
        }
    }

    private SettlementReceived normalize(InboxRow row) throws Exception {
        JsonNode payload = objectMapper.readTree(row.payload());
        return new SettlementReceived(
                1,
                row.notificationId(),
                row.bankId(),
                text(payload, "collection_id"),
                text(payload, "outcome"),
                text(payload, "reason"),
                text(payload, "occurred_at"));
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record InboxRow(UUID id, String bankId, String notificationId, String payload) {
    }
}
