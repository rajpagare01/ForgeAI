-- V4__add_organization_id_to_outbox.sql
-- Source Control Service: Strict multi-tenant outbox isolation, safe quarantine, and indexing

-- 1. Add organization_id column to outbox_events (nullable initially for safe deterministic backfill)
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS organization_id UUID;

-- 2. Safely backfill organization_id from JSONB payload where deterministically known
UPDATE outbox_events
SET organization_id = CASE
    WHEN payload->'payload'->>'organizationId' IS NOT NULL THEN (payload->'payload'->>'organizationId')::uuid
    WHEN payload->>'organizationId' IS NOT NULL THEN (payload->>'organizationId')::uuid
    WHEN payload->'data'->>'organizationId' IS NOT NULL THEN (payload->'data'->>'organizationId')::uuid
    ELSE NULL
END
WHERE organization_id IS NULL;

-- 3. Dedicated quarantine table for events whose tenant ownership cannot be deterministically resolved.
-- Invariant: We NEVER guess tenant ownership, NEVER assign an arbitrary tenant, and NEVER permit unowned events to be replayed.
CREATE TABLE IF NOT EXISTS quarantined_outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64),
    aggregate_id VARCHAR(64),
    event_type VARCHAR(64),
    payload JSONB,
    trace_id VARCHAR(64),
    correlation_id VARCHAR(64),
    created_at TIMESTAMPTZ,
    status VARCHAR(32),
    quarantined_at TIMESTAMPTZ DEFAULT NOW(),
    quarantine_reason TEXT
);

-- Safely move any unresolved records into quarantine table preserving full auditability
INSERT INTO quarantined_outbox_events (id, aggregate_type, aggregate_id, event_type, payload, trace_id, correlation_id, created_at, status, quarantine_reason)
SELECT id, aggregate_type, aggregate_id, event_type, payload, trace_id, correlation_id, created_at, status, 'Unresolved tenant ownership during V4 migration'
FROM outbox_events
WHERE organization_id IS NULL;

DELETE FROM outbox_events WHERE organization_id IS NULL;

-- 4. Enforce strict NOT NULL constraint on organization_id for all remaining outbox events
ALTER TABLE outbox_events ALTER COLUMN organization_id SET NOT NULL;

-- 5. Composite indexes for tenant-scoped operational dead-letter queries and status inspection
CREATE INDEX IF NOT EXISTS idx_sc_outbox_org_status_dead_letter 
    ON outbox_events (organization_id, status, dead_letter_at DESC);

CREATE INDEX IF NOT EXISTS idx_sc_outbox_org_status 
    ON outbox_events (organization_id, status);
