CREATE TABLE renewal_outbox (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  subscription_id UUID NOT NULL REFERENCES subscription(id),
  due_date date NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

-- Fast “to publish” probe:
CREATE INDEX idx_renewal_outbox_unpublished
  ON renewal_outbox (created_at)
  WHERE published_at IS NULL;

-- One charge per subscription per period window
ALTER TABLE charge
  ADD CONSTRAINT uniq_charge_period
  UNIQUE (subscription_id, due_date, amount_cents, currency);

-- Optionally, one invoice per customer+period+currency
ALTER TABLE invoice
  ADD CONSTRAINT uniq_invoice_period
  UNIQUE (customer_id, period_start, period_end, currency);

-- Find due subs quickly by status and renewed_at window
CREATE INDEX idx_subscription_status_renewed_at
  ON subscription (status, renewed_at);

-- Join helper
CREATE INDEX idx_subscription_plan_id ON subscription (plan_id);

-- Active payment methods
CREATE INDEX idx_payment_method_active
  ON payment_method (customer_id, status, expires_on);

-- De-dupe the outbox
ALTER TABLE renewal_outbox
  ADD CONSTRAINT uniq_outbox_sub_due UNIQUE (subscription_id, due_date);
