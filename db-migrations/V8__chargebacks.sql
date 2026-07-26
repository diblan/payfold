-- R23d (D16): a chargeback is a recorded fact on an already-settled payment.
-- The subscription stays advanced and nothing compensates — reacting is
-- dunning (R26). charged_back_at separates the dispute timestamp from
-- completed_at (the settlement timestamp); failure_reason carries MD06.
ALTER TABLE payment ADD COLUMN charged_back_at TIMESTAMPTZ;
