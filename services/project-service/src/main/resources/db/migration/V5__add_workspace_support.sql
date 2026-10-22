-- V5: Add Workspace Support and Link Projects to Workspaces
-- ForgeAI Project Service

-- 1. Create workspaces table in project-service
CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL,
    owner_user_id UUID NOT NULL,
    organization_id UUID NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_proj_workspace_type_enum CHECK (type IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT chk_proj_workspace_type CHECK (
        (type = 'PERSONAL' AND organization_id IS NULL AND owner_user_id IS NOT NULL) OR
        (type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_proj_workspaces_personal_owner 
    ON workspaces (owner_user_id) 
    WHERE type = 'PERSONAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_proj_workspaces_org 
    ON workspaces (organization_id) 
    WHERE type = 'ORGANIZATION';

CREATE INDEX IF NOT EXISTS idx_proj_workspaces_owner ON workspaces (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_proj_workspaces_org ON workspaces (organization_id);

-- 2. Deterministically backfill organization workspaces for every existing organization in projects
INSERT INTO workspaces (id, type, owner_user_id, organization_id, name, created_at, updated_at)
SELECT DISTINCT p.organization_id, 'ORGANIZATION', '00000000-0000-0000-0000-000000000000'::uuid,
       p.organization_id, 'Organization Workspace', NOW(), NOW()
FROM projects p
WHERE p.organization_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

-- 3. Add workspace_id column to projects
ALTER TABLE projects ADD COLUMN IF NOT EXISTS workspace_id UUID;

-- 4. Backfill projects.workspace_id from corresponding organization workspaces
UPDATE projects p
SET workspace_id = w.id
FROM workspaces w
WHERE w.organization_id = p.organization_id AND w.type = 'ORGANIZATION'
  AND p.workspace_id IS NULL;

-- 5. Enforce NOT NULL and Foreign Key on projects.workspace_id
ALTER TABLE projects ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS fk_projects_workspace;
ALTER TABLE projects ADD CONSTRAINT fk_projects_workspace 
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_projects_workspace ON projects (workspace_id);

-- 6. Allow nullable organization_id on projects for PERSONAL projects while preserving for ORGANIZATION projects
ALTER TABLE projects ALTER COLUMN organization_id DROP NOT NULL;

-- 7. Update uniqueness constraint: Key is unique per workspace
ALTER TABLE projects DROP CONSTRAINT IF EXISTS uq_projects_org_key;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS uq_projects_workspace_key;
ALTER TABLE projects ADD CONSTRAINT uq_projects_workspace_key UNIQUE (workspace_id, key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_projects_org_key_not_null 
    ON projects (organization_id, key) 
    WHERE organization_id IS NOT NULL;

-- 8. Allow nullable organization_id and add workspace_id to child entities
ALTER TABLE requirements ALTER COLUMN organization_id DROP NOT NULL;
ALTER TABLE requirements ADD COLUMN IF NOT EXISTS workspace_id UUID;
UPDATE requirements r SET workspace_id = p.workspace_id FROM projects p WHERE r.project_id = p.id AND r.workspace_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_req_workspace ON requirements(workspace_id);

ALTER TABLE tasks ALTER COLUMN organization_id DROP NOT NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS workspace_id UUID;
UPDATE tasks t SET workspace_id = p.workspace_id FROM projects p WHERE t.project_id = p.id AND t.workspace_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_tasks_workspace ON tasks(workspace_id);

ALTER TABLE code_links ALTER COLUMN organization_id DROP NOT NULL;
ALTER TABLE code_links ADD COLUMN IF NOT EXISTS workspace_id UUID;
UPDATE code_links c SET workspace_id = t.workspace_id FROM tasks t WHERE c.task_id = t.id AND c.workspace_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_code_links_workspace ON code_links(workspace_id);

-- 9. Allow personal workspace audit events
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE audit_events ALTER COLUMN organization_id DROP NOT NULL;
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_proj_audit_tenant;
ALTER TABLE audit_events ADD CONSTRAINT chk_proj_audit_tenant 
    CHECK (organization_id IS NOT NULL OR workspace_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_proj_audit_ws_created ON audit_events (workspace_id, created_at DESC);
