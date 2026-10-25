-- V8__enhance_github_installations_lifecycle.sql
-- Source Control Service: Enhanced GitHub App installation lifecycle, automatic discovery, and dynamic metadata

-- 1. Add installation discovery and lifecycle tracking columns
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS github_app_id BIGINT;
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS account_id BIGINT;
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS repository_selection VARCHAR(32) NOT NULL DEFAULT 'ALL';
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS claimed_by_user_id UUID;
ALTER TABLE github_installations ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ;

-- 2. Performance and lookup indexes for automatic discovery
CREATE INDEX IF NOT EXISTS idx_gh_install_account_id ON github_installations (account_id);
CREATE INDEX IF NOT EXISTS idx_gh_install_account_login ON github_installations (account_login);
CREATE INDEX IF NOT EXISTS idx_gh_install_status ON github_installations (status);
CREATE INDEX IF NOT EXISTS idx_gh_install_claimed_user ON github_installations (claimed_by_user_id);

-- 3. Scope integrity: allow unassociated discovered/quarantined installations prior to workspace claim
ALTER TABLE github_installations DROP CONSTRAINT IF EXISTS chk_gh_install_scope;
ALTER TABLE github_installations ADD CONSTRAINT chk_gh_install_scope CHECK (
    (status IN ('DISCOVERED', 'VALIDATING', 'CLAIMABLE', 'QUARANTINED') AND workspace_id IS NULL) OR
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);
