-- V4__add_workspace_and_scope_to_testing.sql
-- Testing Service: Add multi-tenant workspace partitioning, personal workspace support, and strict integrity constraints

CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL,
    owner_user_id UUID NOT NULL,
    organization_id UUID NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_test_workspaces_type_enum CHECK (type IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT chk_test_workspaces_type CHECK (
        (type = 'PERSONAL' AND organization_id IS NULL AND owner_user_id IS NOT NULL) OR
        (type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_test_workspaces_personal_owner 
    ON workspaces (owner_user_id) 
    WHERE type = 'PERSONAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_test_workspaces_organization 
    ON workspaces (organization_id) 
    WHERE type = 'ORGANIZATION';

CREATE INDEX IF NOT EXISTS idx_test_workspaces_owner ON workspaces (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_test_workspaces_org ON workspaces (organization_id);

-- 1. test_suites
ALTER TABLE test_suites ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE test_suites ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

UPDATE test_suites 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE test_suites ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE test_suites DROP CONSTRAINT IF EXISTS uq_test_suites_org_repo_name;
CREATE UNIQUE INDEX IF NOT EXISTS uq_test_suites_org_repo_name 
    ON test_suites (organization_id, repository_id, name) 
    WHERE organization_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_test_suites_ws_repo_name 
    ON test_suites (workspace_id, repository_id, name) 
    WHERE scope = 'PERSONAL';

ALTER TABLE test_suites DROP CONSTRAINT IF EXISTS chk_test_suites_scope;
ALTER TABLE test_suites ADD CONSTRAINT chk_test_suites_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_suites_ws ON test_suites (workspace_id);

-- 2. test_cases
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

UPDATE test_cases 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE test_cases ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE test_cases DROP CONSTRAINT IF EXISTS chk_test_cases_scope;
ALTER TABLE test_cases ADD CONSTRAINT chk_test_cases_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_test_cases_ws_req ON test_cases (workspace_id, requirement_key);

-- 3. test_runs
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE test_runs ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

UPDATE test_runs 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE test_runs ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE test_runs DROP CONSTRAINT IF EXISTS chk_test_runs_scope;
ALTER TABLE test_runs ADD CONSTRAINT chk_test_runs_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_runs_ws_commit ON test_runs (workspace_id, commit_sha);
CREATE INDEX IF NOT EXISTS idx_runs_ws_pr ON test_runs (workspace_id, repository_id, pull_request_number);

-- 4. test_results
ALTER TABLE test_results ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE test_results ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

UPDATE test_results 
SET workspace_id = organization_id 
WHERE workspace_id IS NULL;

ALTER TABLE test_results ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE test_results DROP CONSTRAINT IF EXISTS chk_test_results_tenant;
ALTER TABLE test_results ADD CONSTRAINT chk_test_results_tenant CHECK (
    (organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_results_ws_run ON test_results (workspace_id, test_run_id, status);
CREATE INDEX IF NOT EXISTS idx_results_ws_time ON test_results (workspace_id, created_at);

-- 5. outbox_events
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS organization_id UUID;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS chk_testing_outbox_scope;
ALTER TABLE outbox_events ADD CONSTRAINT chk_testing_outbox_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL) OR
    (organization_id IS NULL AND workspace_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_testing_outbox_ws_status ON outbox_events (workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_testing_outbox_scope_status ON outbox_events (scope, status);

-- 6. audit_events
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS workspace_id UUID;
ALTER TABLE audit_events ALTER COLUMN organization_id DROP NOT NULL;

ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS chk_testing_audit_tenant;
ALTER TABLE audit_events ADD CONSTRAINT chk_testing_audit_tenant CHECK (
    (organization_id IS NOT NULL AND workspace_id IS NOT NULL AND workspace_id = organization_id) OR
    (workspace_id IS NOT NULL AND organization_id IS NULL)
);

CREATE INDEX IF NOT EXISTS idx_testing_audit_ws_created ON audit_events (workspace_id, created_at DESC);
