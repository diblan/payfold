-- R23b (D17): customers carry a payment method; SDD customers carry the
-- debtor fields a collection submission needs. Existing rows default to
-- 'card', which keeps every pre-V6 database and test fixture valid.
ALTER TABLE customer
    ADD COLUMN payment_method VARCHAR(10) NOT NULL DEFAULT 'card',
    ADD COLUMN debtor_iban VARCHAR(34),
    ADD COLUMN mandate_reference VARCHAR(35),
    ADD COLUMN country VARCHAR(2);

ALTER TABLE customer ADD CONSTRAINT customer_payment_method_chk
    CHECK (payment_method IN ('card', 'sdd'));

-- An SDD customer without debtor material cannot be billed; make the gap
-- unrepresentable instead of a runtime surprise.
ALTER TABLE customer ADD CONSTRAINT customer_sdd_fields_chk
    CHECK (payment_method <> 'sdd' OR (debtor_iban IS NOT NULL
        AND mandate_reference IS NOT NULL AND country IS NOT NULL));

-- R23b: which counterparty a submitted collection went to, and under which
-- collection id. NULL for card payments (the PSP path has no collection).
ALTER TABLE payment
    ADD COLUMN bank_id VARCHAR(64),
    ADD COLUMN collection_id VARCHAR(64);
