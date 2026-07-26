package com.blanchaert.billing.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SettlementReceived(int schema_version, String notification_id,
        String bank_id, String collection_id, String outcome, String reason,
        String occurred_at) {
}
