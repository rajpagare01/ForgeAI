-- V7__outbox_dead_letter_and_audit.sql
-- Identity Service: Outbox Dead-Letter, Retry Backoff, and Immutable Audit Log

-- 1. Enhance outbox_events
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS dead_letter_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_identity_outbox_poll ON outbox_events (created_at ASC)
    WHERE published_at IS NULL AND (status IS NULL OR status IN ('PENDING', 'RETRYING'));

CREATE INDEX IF NOT EXISTS idx_identity_outbox_dead_letter ON outbox_events (dead_letter_at DESC)
    WHERE status = 'DEAD_LETTER';

-- 2. Immutable Audit Events Table
CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    actor_user_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_identity_audit_org_created ON audit_events (organization_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_identity_audit_entity ON audit_events (organization_id, entity_type, entity_id);

-- 3. PostgreSQL Database-Level Immutability Enforcement
CREATE OR REPLACE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Audit events are immutable records and cannot be deleted. (id: %)', OLD.id
            USING ERRCODE = 'P0001';
    ELSE
        RAISE EXCEPTION 'Audit events are immutable records and cannot be updated. (id: %)', NEW.id
            USING ERRCODE = 'P0001';
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_audit_event_mutation ON audit_events;
CREATE TRIGGER trg_prevent_audit_event_mutation
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION prevent_audit_event_mutation();
