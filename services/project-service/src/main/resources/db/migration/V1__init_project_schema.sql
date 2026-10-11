-- V1__init_project_schema.sql
-- ForgeAI Project Service Initial Schema Migration
-- Strict multi-tenancy: every tenant-owned table and composite index leads with organization_id

-- 1. Local read-only User Directory projection populated via Identity events
CREATE TABLE IF NOT EXISTS user_directory (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_directory_status ON user_directory(status);

-- 2. Projects table
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    key VARCHAR(16) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_projects_org_key UNIQUE (organization_id, key)
);

CREATE INDEX IF NOT EXISTS idx_projects_org ON projects(organization_id);

-- 3. Requirements table with optimistic locking version
CREATE TABLE IF NOT EXISTS requirements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    identifier VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_requirements_proj_identifier UNIQUE (project_id, identifier)
);

CREATE INDEX IF NOT EXISTS idx_req_org_proj ON requirements(organization_id, project_id);

-- 4. Tasks table with optimistic locking version
CREATE TABLE IF NOT EXISTS tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    requirement_id UUID REFERENCES requirements(id) ON DELETE SET NULL,
    task_key VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'TODO',
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    assignee_id UUID NULL,
    reporter_id UUID NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tasks_proj_task_key UNIQUE (project_id, task_key)
);

CREATE INDEX IF NOT EXISTS idx_tasks_lookup ON tasks(organization_id, project_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(organization_id, assignee_id);

-- 5. Code Links table (Traceability Spine: REQ -> TASK -> CodeLink)
CREATE TABLE IF NOT EXISTS code_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    link_type VARCHAR(32) NOT NULL CHECK (link_type IN ('BRANCH', 'COMMIT', 'PULL_REQUEST')),
    external_ref VARCHAR(500) NOT NULL,
    repository_id UUID NOT NULL,
    created_by VARCHAR(32) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_code_links_task_type_ref UNIQUE (task_id, link_type, external_ref)
);

CREATE INDEX IF NOT EXISTS idx_code_links_ref ON code_links(organization_id, external_ref);

-- 6. Transactional Outbox Events table
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_project_outbox_unprocessed 
    ON outbox_events(created_at) 
    WHERE published_at IS NULL;
