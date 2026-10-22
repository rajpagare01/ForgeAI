-- V5__add_workspace_and_scope_to_source_control.sql
-- Source Control Service: Personal Workspace support, explicit outbox scope partitioning, and safe migration

-- 1. GitHub Installations
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

-- Deterministically backfill existing installations: for organizations, workspace_id == organization_id
UPDATE github_installations 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

-- Allow organization_id to be nullable for personal installations, strictly governed by check constraint
ALTER TABLE github_installations ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE github_installations DROP CONSTRAINT IF EXISTS chk_gh_install_scope;
ALTER TABLE github_installations ADD CONSTRAINT chk_gh_install_scope 
    CHECK ((scope = 'ORGANIZATION' AND organization_id IS NOT NULL) OR 
           (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL));

CREATE INDEX IF NOT EXISTS idx_gh_install_workspace ON github_installations (workspace_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_gh_install_personal ON github_installations (workspace_id) WHERE scope = 'PERSONAL';

-- 2. Repositories
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE repositories ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

-- Deterministically backfill existing repositories: workspace_id == organization_id
UPDATE repositories 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE repositories ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE repositories DROP CONSTRAINT IF EXISTS chk_repo_scope;
ALTER TABLE repositories ADD CONSTRAINT chk_repo_scope 
    CHECK ((scope = 'ORGANIZATION' AND organization_id IS NOT NULL) OR 
           (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL));

CREATE INDEX IF NOT EXISTS idx_repo_workspace ON repositories (workspace_id);

-- 3. Pull Requests
ALTER TABLE pull_requests ADD COLUMN IF NOT EXISTS workspace_id UUID;

UPDATE pull_requests pr
SET workspace_id = r.workspace_id
FROM repositories r
WHERE pr.repository_id = r.id AND pr.workspace_id IS NULL;

ALTER TABLE pull_requests ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE pull_requests DROP CONSTRAINT IF EXISTS chk_pr_tenant;
ALTER TABLE pull_requests ADD CONSTRAINT chk_pr_tenant 
    CHECK (organization_id IS NOT NULL OR workspace_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_pr_workspace_repo ON pull_requests (workspace_id, repository_id);

-- 4. Commits
ALTER TABLE commits ADD COLUMN IF NOT EXISTS workspace_id UUID;

UPDATE commits c
SET workspace_id = r.workspace_id
FROM repositories r
WHERE c.repository_id = r.id AND c.workspace_id IS NULL;

ALTER TABLE commits ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE commits DROP CONSTRAINT IF EXISTS chk_commits_tenant;
ALTER TABLE commits ADD CONSTRAINT chk_commits_tenant 
    CHECK (organization_id IS NOT NULL OR workspace_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_commits_workspace_repo ON commits (workspace_id, repository_id);

-- 5. Outbox Events Partitioning & Scope
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

-- Deterministically backfill existing events
UPDATE outbox_events 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE outbox_events ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_sc_outbox_scope;
ALTER TABLE outbox_events ADD CONSTRAINT chk_sc_outbox_scope 
    CHECK ((scope = 'ORGANIZATION' AND organization_id IS NOT NULL) OR 
           (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL));

CREATE INDEX IF NOT EXISTS idx_sc_outbox_ws_status ON outbox_events (workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_sc_outbox_scope_status ON outbox_events (scope, status);
