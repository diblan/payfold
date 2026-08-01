-- R26b (D20): re-collections are new payment rows through the same spine.
-- attempt disambiguates rows inside a charge's collection-id family; the
-- unique key is what makes duplicate sweeper ticks physically unable to
-- double-submit an attempt.
ALTER TABLE payment ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1;
ALTER TABLE payment ADD CONSTRAINT uniq_payment_charge_attempt
    UNIQUE (charge_id, attempt);
