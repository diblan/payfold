-- A yearly plan so due-today seeding has a valid renewal preimage on month-end
-- clamp days: on Jul 31 no monthly renewed_at can be due (Jun 31 is not a date,
-- and Jun 30 + 1 month clamps to Jul 30), while Jul 31 last year + 1 year lands
-- exactly on Jul 31. The seeder weights this plan at zero, so it is only used
-- where the monthly round-trip fails and demo distributions stay unchanged.
INSERT INTO plan (name, interval, price_cents, currency, tax_category, proration) VALUES
  ('Premium Annual', 'year', 19990, 'EUR', 'streaming', TRUE);
