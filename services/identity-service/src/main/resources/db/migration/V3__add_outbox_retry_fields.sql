-- V3__add_outbox_retry_fields.sql
-- Add retry attempt count, last error, and next retry scheduling for outbox events

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_error TEXT;

-- Drop and recreate the partial index to include attempt_count
DROP INDEX IF EXISTS idx_outbox_unprocessed;
CREATE INDEX idx_outbox_unprocessed ON outbox_events (created_at ASC) WHERE published_at IS NULL;
