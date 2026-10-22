-- V6__workspace_integrity_constraints.sql
-- Project Service: Enforce database-level invariants preventing invalid workspace and organization tenant states

-- 1. Ensure projects strictly adhere to workspace invariants
-- PERSONAL: organization_id IS NULL AND workspace_id IS NOT NULL
-- ORGANIZATION: organization_id IS NOT NULL AND workspace_id = organization_id
ALTER TABLE projects DROP CONSTRAINT IF EXISTS chk_projects_workspace_tenant;
ALTER TABLE projects ADD CONSTRAINT chk_projects_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

-- 2. Requirements integrity
UPDATE requirements r
SET workspace_id = p.workspace_id
FROM projects p
WHERE r.project_id = p.id AND (r.workspace_id IS NULL OR (r.organization_id IS NOT NULL AND r.workspace_id != r.organization_id));

ALTER TABLE requirements ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE requirements DROP CONSTRAINT IF EXISTS chk_requirements_workspace_tenant;
ALTER TABLE requirements ADD CONSTRAINT chk_requirements_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

-- 3. Tasks integrity
UPDATE tasks t
SET workspace_id = p.workspace_id
FROM projects p
WHERE t.project_id = p.id AND (t.workspace_id IS NULL OR (t.organization_id IS NOT NULL AND t.workspace_id != t.organization_id));

ALTER TABLE tasks ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS chk_tasks_workspace_tenant;
ALTER TABLE tasks ADD CONSTRAINT chk_tasks_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

-- 4. Code Links integrity
UPDATE code_links c
SET workspace_id = t.workspace_id
FROM tasks t
WHERE c.task_id = t.id AND (c.workspace_id IS NULL OR (c.organization_id IS NOT NULL AND c.workspace_id != c.organization_id));

ALTER TABLE code_links ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE code_links DROP CONSTRAINT IF EXISTS chk_code_links_workspace_tenant;
ALTER TABLE code_links ADD CONSTRAINT chk_code_links_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);

-- 5. Audit Events integrity
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_proj_audit_tenant;
ALTER TABLE audit_events ADD CONSTRAINT chk_proj_audit_tenant CHECK (
    (organization_id IS NOT NULL AND (workspace_id IS NULL OR workspace_id = organization_id)) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

-- 6. Project Repository Links integrity
ALTER TABLE project_repository_links ADD COLUMN IF NOT EXISTS workspace_id UUID;
UPDATE project_repository_links prl
SET workspace_id = p.workspace_id
FROM projects p
WHERE prl.project_id = p.id AND (prl.workspace_id IS NULL OR (prl.organization_id IS NOT NULL AND prl.workspace_id != prl.organization_id));

ALTER TABLE project_repository_links ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE project_repository_links ALTER COLUMN organization_id DROP NOT NULL;
ALTER TABLE project_repository_links DROP CONSTRAINT IF EXISTS chk_project_repo_links_workspace_tenant;
ALTER TABLE project_repository_links ADD CONSTRAINT chk_project_repo_links_workspace_tenant CHECK (
    workspace_id IS NOT NULL AND (organization_id IS NULL OR workspace_id = organization_id)
);
