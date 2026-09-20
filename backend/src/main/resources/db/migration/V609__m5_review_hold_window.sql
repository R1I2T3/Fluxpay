-- Existing review cases were created before approval_expires_at became an enforced review hold.
-- Start their 24-hour compatibility window from the time they entered their current review state.
UPDATE payments
SET approval_expires_at = updated_at + INTERVAL '24' HOUR
WHERE status = 'UNDER_REVIEW'
  AND approval_expires_at IS NULL;
