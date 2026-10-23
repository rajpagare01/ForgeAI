-- V7__add_workspaces_to_source_control.sql
-- Source Control Service: Multi-tenant workspace table and isolation constraints

CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL,
    owner_user_id UUID NOT NULL,
    organization_id UUID NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sc_workspaces_type_enum CHECK (type IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT chk_sc_workspaces_type CHECK (
        (type = 'PERSONAL' AND organization_id IS NULL AND owner_user_id IS NOT NULL) OR
        (type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sc_workspaces_personal_owner 
    ON workspaces (owner_user_id) 
    WHERE type = 'PERSONAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_sc_workspaces_organization 
    ON workspaces (organization_id) 
    WHERE type = 'ORGANIZATION';

CREATE INDEX IF NOT EXISTS idx_sc_workspaces_owner ON workspaces (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_sc_workspaces_org ON workspaces (organization_id);

-- Add workspace_id to audit_events and allow organization_id to be nullable for personal operations
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE audit_events ALTER COLUMN organization_id DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sc_audit_ws_created ON audit_events (workspace_id, created_at DESC);
