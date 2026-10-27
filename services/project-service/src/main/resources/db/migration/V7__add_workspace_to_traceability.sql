-- V7__add_workspace_to_traceability.sql
-- Project Service: Add workspace partitioning and personal workspace support to traceability links and snapshots

-- 1. traceability_links
ALTER TABLE traceability_links ADD COLUMN IF NOT EXISTS workspace_id UUID;

UPDATE traceability_links tl
SET workspace_id = p.workspace_id
FROM projects p
WHERE tl.project_id = p.id AND tl.workspace_id IS NULL;

-- Backfill fallback if any link had no project found
UPDATE traceability_links
SET workspace_id = organization_id
WHERE workspace_id IS NULL;

ALTER TABLE traceability_links ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE traceability_links ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE traceability_links DROP CONSTRAINT IF EXISTS uq_trace_link;
ALTER TABLE traceability_links DROP CONSTRAINT IF EXISTS uq_trace_link_workspace;
ALTER TABLE traceability_links ADD CONSTRAINT uq_trace_link_workspace 
    UNIQUE (workspace_id, project_id, source_type, source_id, target_type, target_id, link_origin);

ALTER TABLE traceability_links DROP CONSTRAINT IF EXISTS chk_trace_link_workspace_tenant;
ALTER TABLE traceability_links ADD CONSTRAINT chk_trace_link_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

CREATE INDEX IF NOT EXISTS idx_trace_links_ws_proj ON traceability_links (workspace_id, project_id);

-- 2. traceability_snapshots
ALTER TABLE traceability_snapshots ADD COLUMN IF NOT EXISTS workspace_id UUID;

ALTER TABLE traceability_snapshots DISABLE TRIGGER trg_prevent_traceability_snapshot_mutation;

UPDATE traceability_snapshots ts
SET workspace_id = p.workspace_id
FROM projects p
WHERE ts.project_id = p.id AND ts.workspace_id IS NULL;

-- Backfill fallback
UPDATE traceability_snapshots
SET workspace_id = organization_id
WHERE workspace_id IS NULL;

ALTER TABLE traceability_snapshots ENABLE TRIGGER trg_prevent_traceability_snapshot_mutation;

ALTER TABLE traceability_snapshots ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE traceability_snapshots ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE traceability_snapshots DROP CONSTRAINT IF EXISTS chk_trace_snapshot_workspace_tenant;
ALTER TABLE traceability_snapshots ADD CONSTRAINT chk_trace_snapshot_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

CREATE INDEX IF NOT EXISTS idx_trace_snapshot_ws_proj ON traceability_snapshots (workspace_id, project_id);
