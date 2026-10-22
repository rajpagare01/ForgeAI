-- V6__workspace_integrity_constraints.sql
-- Source Control Service: Strict database-level check constraints for installations, repositories, PRs, commits, and outbox

-- 1. GitHub Installations
ALTER TABLE github_installations DROP CONSTRAINT IF EXISTS chk_gh_install_scope;
ALTER TABLE github_installations ADD CONSTRAINT chk_gh_install_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);

-- 2. Repositories
ALTER TABLE repositories DROP CONSTRAINT IF EXISTS chk_repo_scope;
ALTER TABLE repositories ADD CONSTRAINT chk_repo_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);

-- 3. Pull Requests
ALTER TABLE pull_requests DROP CONSTRAINT IF EXISTS chk_pr_tenant;
ALTER TABLE pull_requests ADD CONSTRAINT chk_pr_tenant CHECK (
    (organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

-- 4. Commits
ALTER TABLE commits DROP CONSTRAINT IF EXISTS chk_commits_tenant;
ALTER TABLE commits ADD CONSTRAINT chk_commits_tenant CHECK (
    (organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

-- 5. Outbox Events Partitioning & Scope
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_sc_outbox_scope;
ALTER TABLE outbox_events ADD CONSTRAINT chk_sc_outbox_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);
