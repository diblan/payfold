package com.blanchaert.billing.consumer.model;

import java.util.UUID;

public record RenewalRequested(
        UUID subscription_id,
        UUID customer_id,
        UUID plan_id,
        String interval, // "month" | "year"
        long amount_cents,
        String currency,
        String idempotency_key, // optional if publisher fills it; we can compute it if null
        String period_start, // ISO date (yyyy-MM-dd)
        String period_end // ISO date
) {
}
