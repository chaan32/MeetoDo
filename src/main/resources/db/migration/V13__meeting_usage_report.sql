-- Existing meetings have no reported usage; do not backfill estimates.
ALTER TABLE meetings ADD COLUMN usage_report text;
