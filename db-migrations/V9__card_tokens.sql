-- R23f (D17): card customers carry a tokenized card reference; the mock card
-- scheme derives its auth verdict from the token's last two chars, so
-- verification can predict every card outcome from stored data alone.
ALTER TABLE customer ADD COLUMN card_token VARCHAR(64);

-- Legacy card customers (pre-V9 databases) get a token derived from their
-- immutable id: outcome shares for such rows are whatever the id's tail hex
-- happens to be, and verification predicts from the STORED token, so any
-- backfill value is honest.
UPDATE customer SET card_token = 'tok-' || replace(id::text, '-', '')
WHERE payment_method = 'card' AND card_token IS NULL;

ALTER TABLE customer ADD CONSTRAINT customer_card_token_chk
    CHECK (payment_method <> 'card' OR card_token IS NOT NULL);
