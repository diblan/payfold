-- R23c (D15): durable inbox for bank settlement notifications. The webhook
-- 200 is truthful only because this row is durable before it is sent; the
-- unique constraint makes bank redelivery a no-op. The row doubles as its own
-- outbox: published_at is set once the settlement message is confirmed on the
-- settlements queue (the D1 dual-write answer, mirrored inbound).
CREATE TABLE settlement_inbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id         VARCHAR(64) NOT NULL,
    notification_id VARCHAR(128) NOT NULL,
    payload         JSONB NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT uniq_settlement_notification UNIQUE (bank_id, notification_id)
);

CREATE INDEX idx_settlement_inbox_unpublished
    ON settlement_inbox (received_at) WHERE published_at IS NULL;

-- Terminal failure vocabulary (D16: record outcomes richly enough that
-- dunning needs no schema rework): the ISO reason a failed collection carried.
ALTER TABLE payment ADD COLUMN failure_reason VARCHAR(64);
