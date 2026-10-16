-- V2__traceability_and_project_intelligence.sql
-- ForgeAI Project Service: Unified Engineering Traceability & Project Intelligence
-- Strict multi-tenancy: every tenant-owned table and composite index leads with organization_id

-- 1. Project to Repository Links (Multi-repository projects support)
CREATE TABLE IF NOT EXISTS project_repository_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL,
    repository_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_repo_link UNIQUE (organization_id, project_id, repository_id)
);

CREATE INDEX IF NOT EXISTS idx_proj_repo_org_proj ON project_repository_links(organization_id, project_id);
CREATE INDEX IF NOT EXISTS idx_proj_repo_lookup ON project_repository_links(organization_id, repository_id);

-- 2. Traceability Links (Unbroken delivery spine: REQ -> TASK -> CODE -> COMMIT -> PR -> TEST -> AI_ANALYSIS -> REMEDIATION)
CREATE TABLE IF NOT EXISTS traceability_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    repository_id UUID NULL,
    external_reference VARCHAR(500) NULL,
    link_origin VARCHAR(64) NOT NULL,
    confidence VARCHAR(32) NOT NULL DEFAULT 'HIGH',
    evidence TEXT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_trace_link UNIQUE (organization_id, project_id, source_type, source_id, target_type, target_id, link_origin)
);

CREATE INDEX IF NOT EXISTS idx_trace_links_org_proj ON traceability_links(organization_id, project_id);
CREATE INDEX IF NOT EXISTS idx_trace_links_source ON traceability_links(organization_id, source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_trace_links_target ON traceability_links(organization_id, target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_trace_links_repo ON traceability_links(organization_id, repository_id);

-- 3. Traceability Snapshots (Immutable historical snapshots for auditable engineering governance)
CREATE TABLE IF NOT EXISTS traceability_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    snapshot_name VARCHAR(255) NOT NULL,
    pr_number INT NULL,
    commit_sha VARCHAR(64) NULL,
    repository_id UUID NULL,
    snapshot_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trace_snapshot_org_proj ON traceability_snapshots(organization_id, project_id);
CREATE INDEX IF NOT EXISTS idx_trace_snapshot_pr ON traceability_snapshots(organization_id, repository_id, pr_number);
