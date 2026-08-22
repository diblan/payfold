package com.blanchaert.billing.consumer.mq;

import com.blanchaert.billing.consumer.config.RelayProperties;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class SettlementInboxRelay {
    private static final Logger log = LoggerFactory.getLogger(SettlementInboxRelay.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RelayProperties properties;

    public SettlementInboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate,
                                ObjectMapper objectMapper, RelayProperties properties) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relayOnce() {
        List<InboxRow> rows = jdbc.query("""
                        SELECT id, bank_id, notification_id, payload
                        FROM settlement_inbox
                        WHERE published_at IS NULL
                        ORDER BY received_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> new InboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("bank_id"),
                        rs.getString("notification_id"),
                        rs.getString("payload")),
                properties.pageSize());
        if (rows.isEmpty()) {
            return;
        }

        // One deadline spans the send loop and the confirm await; the window is
        // page-scoped so a stalled page can never leak permits into later ones
        // (D24). Permit release rides confirm-future completion, which
        // spring-rabbit performs synchronously with ack delivery (D14).
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(properties.confirmTimeoutMs());
        Semaphore window = new Semaphore(properties.inFlightLimit());
        List<PendingPublish> pending = new ArrayList<>(rows.size());
        for (InboxRow row : rows) {
            try {
                SettlementReceived event = normalize(row);
                Message message = MessageBuilder
                        .withBody(objectMapper.writeValueAsBytes(event))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build();
                if (!window.tryAcquire(remaining(deadlineNanos), TimeUnit.NANOSECONDS)) {
                    log.warn("Settlement relay confirm window stalled to the page deadline"
                                    + " before inbox row {}; unsent rows stay unpublished",
                            row.id());
                    break;
                }
                CorrelationData correlationData = new CorrelationData();
                correlationData.getFuture().whenComplete(
                        (confirm, cause) -> window.release());
                rabbitTemplate.convertAndSend(
                        SettlementTopology.EXCHANGE,
                        SettlementTopology.ROUTING_KEY,
                        message,
                        correlationData);
                pending.add(new PendingPublish(row, correlationData));
            } catch (Exception exception) {
                log.warn("Settlement relay stopped sending at inbox row {}",
                        row.id(), exception);
                break;
            }
        }

        List<UUID> acked = new ArrayList<>(pending.size());
        int unconfirmed = 0;
        for (PendingPublish publish : pending) {
            try {
                CorrelationData.Confirm confirm = publish.correlation().getFuture()
                        .get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
                if (confirm.isAck()) {
                    acked.add(publish.row().id());
                } else {
                    // Siblings keep awaiting: their acks still mark, and the
                    // nacked row stays unpublished for the next tick.
                    log.warn("Settlement relay publish nacked for inbox row {}: {}",
                            publish.row().id(), confirm.getReason());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Settlement relay interrupted awaiting confirms;"
                        + " unmarked rows re-pick next tick");
                break;
            } catch (Exception exception) {
                // Past the deadline every remaining get() returns instantly, so
                // already-confirmed siblings still mark; this row stays
                // unpublished for the next tick.
                unconfirmed++;
            }
        }
        if (unconfirmed > 0) {
            log.warn("Settlement relay page deadline passed with {} of {} sends"
                            + " unconfirmed; unconfirmed rows stay unpublished",
                    unconfirmed, pending.size());
        }
        if (!acked.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE settlement_inbox
                    SET published_at = now()
                    WHERE id = ?
                    """, acked.stream().map(id -> new Object[] {id}).toList());
        }
    }

    private long remaining(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
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

    private record PendingPublish(InboxRow row, CorrelationData correlation) {
    }
}
