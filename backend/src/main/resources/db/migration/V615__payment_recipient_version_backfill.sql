-- Backfill legacy payments that were seeded without recipient_version (V603/V612).
-- The Payment entity now tolerates NULL as 0, but old rows must not stay NULL.
UPDATE payments SET recipient_version = 0 WHERE recipient_version IS NULL;
COMMIT;
