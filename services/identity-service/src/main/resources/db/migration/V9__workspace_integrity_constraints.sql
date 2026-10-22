-- V9__workspace_integrity_constraints.sql
-- Identity Service: Workspace integrity invariants and personal audit support

-- 1. Tighten workspaces constraint: For ORGANIZATION workspaces, id MUST equal organization_id
ALTER TABLE workspaces DROP CONSTRAINT IF EXISTS chk_workspaces_type;
ALTER TABLE workspaces ADD CONSTRAINT chk_workspaces_type CHECK (
    (type = 'PERSONAL' AND organization_id IS NULL AND owner_user_id IS NOT NULL) OR
    (type = 'ORGANIZATION' AND organization_id IS NOT NULL AND id = organization_id)
);

-- 2. Audit Events personal workspace support
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE audit_events ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_identity_audit_scope;
ALTER TABLE audit_events ADD CONSTRAINT chk_identity_audit_scope CHECK (
    (organization_id IS NOT NULL AND (workspace_id IS NULL OR workspace_id = organization_id)) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_identity_audit_ws_created ON audit_events (workspace_id, created_at DESC);
