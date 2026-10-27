-- V9: Secure GitHub App installation state tracking for single-use, anti-replay, and tenant binding

CREATE TABLE IF NOT EXISTS github_installation_states (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    state VARCHAR(128) NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    organization_id UUID,
    scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_gh_install_states_state ON github_installation_states (state);
CREATE INDEX IF NOT EXISTS idx_gh_install_states_user ON github_installation_states (user_id);
CREATE INDEX IF NOT EXISTS idx_gh_install_states_workspace ON github_installation_states (workspace_id);

