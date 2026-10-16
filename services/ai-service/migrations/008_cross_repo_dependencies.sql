-- Migration 008: Cross-Repository Dependency & Distributed Change Intelligence
-- Creates persistent storage for cross-repository relationships, dependency evidence,
-- and distributed impact records. Tenant isolation enforced on every row.
-- All dependency relationships are ADDITIVE — no existing tables are altered.

-- ============================================================
-- 1. repository_dependencies
-- Persists every discovered or explicit cross-repository relationship.
-- Lifecycle: ACTIVE → STALE → REMOVED (never hard-deleted while historical).
-- ============================================================
CREATE TABLE IF NOT EXISTS repository_dependencies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    source_repository_id UUID NOT NULL,                        -- the repo that depends
    target_repository_id UUID NOT NULL,                        -- the repo being depended upon
    dependency_type     VARCHAR(50)  NOT NULL,                 -- HTTP_API, SHARED_LIBRARY, EVENT_CONTRACT, SHARED_SCHEMA, DATABASE_CONTRACT, OTHER_EXPLICIT
    evidence_type       VARCHAR(20)  NOT NULL,                 -- EXPLICIT | DISCOVERED
    confidence          VARCHAR(20)  NOT NULL,                 -- HIGH | MEDIUM | LOW
    source_reference    TEXT,                                  -- e.g. pom.xml coordinate, OpenAPI path ref
    target_reference    TEXT,                                  -- e.g. endpoint path, artifact id
    current_state       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | STALE | REMOVED
    metadata            JSONB        NOT NULL DEFAULT '{}',
    discovered_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_verified_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Deterministic uniqueness: one active dependency record per (org, src, tgt, type, ref)
    CONSTRAINT uq_repo_dep_active UNIQUE NULLS NOT DISTINCT (
        organization_id, source_repository_id, target_repository_id,
        dependency_type, source_reference
    )
);

-- Tenant-leading indices (strict isolation)
CREATE INDEX IF NOT EXISTS idx_repo_deps_org_source
    ON repository_dependencies (organization_id, source_repository_id);

CREATE INDEX IF NOT EXISTS idx_repo_deps_org_target
    ON repository_dependencies (organization_id, target_repository_id);

CREATE INDEX IF NOT EXISTS idx_repo_deps_state
    ON repository_dependencies (organization_id, current_state);

-- ============================================================
-- 2. dependency_evidence
-- One raw evidence record per manifest file, endpoint ref, or schema file
-- that proves a repository_dependencies row.
-- ============================================================
CREATE TABLE IF NOT EXISTS dependency_evidence (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    dependency_id   UUID NOT NULL REFERENCES repository_dependencies(id) ON DELETE CASCADE,
    evidence_kind   VARCHAR(100),   -- MAVEN_POM, REQUIREMENTS_TXT, PACKAGE_JSON, OPENAPI_REF, DOCKERFILE_URL, ENV_URL, PROTO_IMPORT
    file_path       VARCHAR(500),
    excerpt         TEXT,           -- short text excerpt proving the dependency (never full file)
    commit_sha      VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dep_evidence_dep_id
    ON dependency_evidence (organization_id, dependency_id);

-- ============================================================
-- 3. distributed_impact_records
-- Persists the distributed impact summary produced during each PR analysis
-- for audit and historical review. Linked to analyses(id).
-- ============================================================
CREATE TABLE IF NOT EXISTS distributed_impact_records (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL,
    analysis_id             UUID REFERENCES analyses(id) ON DELETE CASCADE,
    source_repository_id    UUID NOT NULL,
    pr_number               INT,
    commit_sha              VARCHAR(40),
    direct_downstream_count INT  NOT NULL DEFAULT 0,
    transitive_downstream_count INT NOT NULL DEFAULT 0,
    max_cross_repo_depth    INT  NOT NULL DEFAULT 0,
    contract_status         VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',   -- COMPATIBLE, POTENTIALLY_BREAKING, BREAKING, UNKNOWN
    distributed_risk_score  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    distributed_risk_level  VARCHAR(20) NOT NULL DEFAULT 'NONE',      -- NONE, LOW, MEDIUM, HIGH
    downstream_summary      JSONB NOT NULL DEFAULT '[]',              -- list of downstream repo impacts
    provenance              JSONB NOT NULL DEFAULT '[]',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dist_impact_org_analysis
    ON distributed_impact_records (organization_id, analysis_id);

CREATE INDEX IF NOT EXISTS idx_dist_impact_org_repo_pr
    ON distributed_impact_records (organization_id, source_repository_id, pr_number);
