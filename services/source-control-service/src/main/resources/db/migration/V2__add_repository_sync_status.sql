-- V2__add_repository_sync_status.sql
-- Adds synchronization state and metadata columns to repositories table

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS latest_observed_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS latest_indexed_sha VARCHAR(64),
    ADD COLUMN IF NOT EXISTS sync_status VARCHAR(32) NOT NULL DEFAULT 'SYNCED',
    ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS last_delivery_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_repo_sync_status ON repositories (organization_id, sync_status);
