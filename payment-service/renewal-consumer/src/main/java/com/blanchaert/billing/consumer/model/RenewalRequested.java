package com.blanchaert.billing.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RenewalRequested(
        int schema_version,
        UUID event_id,
        UUID subscription_id,
        UUID customer_id,
        UUID plan_id,
        String interval, // "month" | "year"
        long amount_cents,
        String currency,
        String idempotency_key, // producer-supplied stable key
        String due_date, // ISO date (yyyy-MM-dd)
        String period_start, // ISO date (yyyy-MM-dd)
        String period_end, // ISO date (yyyy-MM-dd)
        String occurred_at // ISO-8601 timestamp with zone information
) {
}
