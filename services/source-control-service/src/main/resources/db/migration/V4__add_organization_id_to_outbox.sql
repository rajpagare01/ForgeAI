-- V4__add_organization_id_to_outbox.sql
-- Source Control Service: Strict multi-tenant outbox isolation and indexing

-- 1. Add organization_id column to outbox_events
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS organization_id UUID;

-- 2. Safely backfill organization_id from JSONB payload
UPDATE outbox_events
SET organization_id = CASE
    WHEN payload->'payload'->>'organizationId' IS NOT NULL THEN (payload->'payload'->>'organizationId')::uuid
    WHEN payload->>'organizationId' IS NOT NULL THEN (payload->>'organizationId')::uuid
    WHEN payload->'data'->>'organizationId' IS NOT NULL THEN (payload->'data'->>'organizationId')::uuid
    ELSE NULL
END
WHERE organization_id IS NULL;

-- 3. Composite indexes for tenant-scoped operational dead-letter queries and status inspection
CREATE INDEX IF NOT EXISTS idx_sc_outbox_org_status_dead_letter 
    ON outbox_events (organization_id, status, dead_letter_at DESC);

CREATE INDEX IF NOT EXISTS idx_sc_outbox_org_status 
    ON outbox_events (organization_id, status);
