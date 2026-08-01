-- R26a (D16): terminal payment outcomes move the subscription into a past_due
-- grace lifecycle. One deadline column carries every class's grace; the class
-- itself stays config-shaped in the consumer (policies are code, outcomes are
-- data). 'past_due' joins the status vocabulary alongside active/paused/canceled.
ALTER TABLE subscription ADD COLUMN grace_until TIMESTAMPTZ;

-- The ops gauge polls count(status = 'past_due') on a short interval; the
-- partial index keeps that an index-only scan at any subscription count.
CREATE INDEX idx_subscription_past_due ON subscription (id)
    WHERE status = 'past_due';
