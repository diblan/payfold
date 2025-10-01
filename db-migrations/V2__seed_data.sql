-- Netflix plans (Belgium) — prices in euro cents (incl. VAT as listed to consumers)
INSERT INTO plan (name, interval, price_cents, currency, tax_category, proration) VALUES
  ('Basic',    'month',  999, 'EUR', 'streaming', TRUE),
  ('Standard', 'month', 1499, 'EUR', 'streaming', TRUE),
  ('Premium',  'month', 1999, 'EUR', 'streaming', TRUE);
