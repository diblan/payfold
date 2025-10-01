-- ===============================
--  Customers & Mandates
-- ===============================

CREATE TABLE customer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    name            VARCHAR(255),
    locale          VARCHAR(10) DEFAULT 'en',
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mandate (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id),
    scheme          VARCHAR(20) NOT NULL,   -- e.g. SEPA_CORE, B2B
    mandate_ref     VARCHAR(64) NOT NULL UNIQUE,
    iban_hash       VARCHAR(128) NOT NULL,
    status          VARCHAR(20) NOT NULL,   -- active, revoked
    signed_at       TIMESTAMPTZ
);

CREATE TABLE payment_method (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id),
    type            VARCHAR(20) NOT NULL,   -- SEPA, CARD
    token           VARCHAR(255),           -- PSP token/alias
    fingerprint     VARCHAR(128),
    status          VARCHAR(20) NOT NULL,   -- active, expired
    expires_on      DATE
);

-- ===============================
--  Plans & Subscriptions
-- ===============================

CREATE TABLE plan (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    interval        VARCHAR(20) NOT NULL,   -- month, year
    price_cents     BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    tax_category    VARCHAR(50),
    proration       BOOLEAN DEFAULT TRUE
);

CREATE TABLE subscription (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id),
    plan_id         UUID NOT NULL REFERENCES plan(id),
    status          VARCHAR(20) NOT NULL,   -- active, paused, canceled
    start_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancel_at       TIMESTAMPTZ,
    renewed_at      TIMESTAMPTZ
);

-- ===============================
--  Invoices & Charges
-- ===============================

CREATE TABLE invoice (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID NOT NULL REFERENCES customer(id),
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    total_cents     BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    number          VARCHAR(50) UNIQUE,    -- human-readable invoice number
    status          VARCHAR(20) NOT NULL,  -- draft, posted, paid, void
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE charge (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID REFERENCES subscription(id),
    invoice_id      UUID REFERENCES invoice(id),
    amount_cents    BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    description     VARCHAR(255),
    status          VARCHAR(20) NOT NULL,  -- pending, settled
    due_date        DATE
);

-- ===============================
--  Payments & Bank Transactions
-- ===============================

CREATE TABLE payment (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_id         UUID REFERENCES charge(id),
    payment_method_id UUID REFERENCES payment_method(id),
    amount_cents      BIGINT NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    channel           VARCHAR(20) NOT NULL, -- SEPA_DD, CARD, BANK_TRANSFER
    idempotency_key   VARCHAR(64) UNIQUE,
    provider_ref      VARCHAR(128),         -- PSP or bank reference
    status            VARCHAR(20) NOT NULL, -- pending, succeeded, failed
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE TABLE bank_tx (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_date         DATE NOT NULL,
    value_date           DATE,
    amount_cents         BIGINT NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    end_to_end_id        VARCHAR(64),
    remittance_info      VARCHAR(255),
    counterparty_iban    VARCHAR(64),
    source               VARCHAR(20) NOT NULL, -- CAMT053, CAMT054, PSP
    status               VARCHAR(20) NOT NULL, -- booked, reversed
    raw_blob             JSONB
);

-- ===============================
--  Reconciliation & Ledger
-- ===============================

CREATE TABLE recon_match (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_tx_id     UUID NOT NULL REFERENCES bank_tx(id),
    invoice_id     UUID REFERENCES invoice(id),
    charge_id      UUID REFERENCES charge(id),
    payment_id     UUID REFERENCES payment(id),
    applied_cents  BIGINT NOT NULL,
    matched_by     VARCHAR(20) NOT NULL,    -- RULE, MANUAL
    confidence     NUMERIC(5,2),
    matched_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal       VARCHAR(50) NOT NULL,  -- AR, CASH, REVENUE
    account_dr    VARCHAR(50) NOT NULL,
    account_cr    VARCHAR(50) NOT NULL,
    amount_cents  BIGINT NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    ref_type      VARCHAR(20),           -- invoice, payment, etc.
    ref_id        UUID,
    posted_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
