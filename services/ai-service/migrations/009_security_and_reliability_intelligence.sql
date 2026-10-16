-- Migration 009: Security, Reliability & Compliance Intelligence Engine
-- Creates persistent storage for deterministic security findings, reliability findings,
-- security assessments, reliability assessments, and unified risk scores.
-- Tenant isolation enforced on every table and leading index.

-- 1. security_findings
CREATE TABLE IF NOT EXISTS security_findings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    analysis_id         UUID REFERENCES analyses(id) ON DELETE CASCADE,
    pr_number           INT NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    category            VARCHAR(64) NOT NULL,                  -- SECRET_EXPOSURE, INSECURE_CONFIGURATION, INJECTION, CODE_LEVEL_SECURITY, DEPENDENCY_RISK, etc.
    rule_id             VARCHAR(128) NOT NULL,                 -- e.g. SEC_SECRET_API_KEY, SEC_TLS_VERIFY_DISABLED, SEC_SQL_CONCAT
    severity            VARCHAR(32) NOT NULL,                  -- CRITICAL, HIGH, MEDIUM, LOW, INFO
    confidence          VARCHAR(32) NOT NULL,                  -- HIGH, MEDIUM, LOW
    title               TEXT NOT NULL,
    description         TEXT NOT NULL,
    file_path           TEXT NOT NULL,
    line_number         INT,
    redacted_evidence   TEXT NOT NULL,                         -- strictly redacted, raw secrets NEVER persisted
    remediation         TEXT NOT NULL,
    is_blocker          BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sec_findings_org_repo_pr
    ON security_findings (organization_id, repository_id, pr_number);

CREATE INDEX IF NOT EXISTS idx_sec_findings_severity
    ON security_findings (organization_id, severity);

-- 2. reliability_findings
CREATE TABLE IF NOT EXISTS reliability_findings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    analysis_id         UUID REFERENCES analyses(id) ON DELETE CASCADE,
    pr_number           INT NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    category            VARCHAR(64) NOT NULL,                  -- ERROR_HANDLING, TIMEOUT, RETRY, CONCURRENCY, RESOURCE_LEAK, FAILURE_RISK, etc.
    rule_id             VARCHAR(128) NOT NULL,                 -- e.g. REL_SWALLOWED_EXCEPTION, REL_MISSING_HTTP_TIMEOUT
    severity            VARCHAR(32) NOT NULL,                  -- CRITICAL, HIGH, MEDIUM, LOW, INFO
    confidence          VARCHAR(32) NOT NULL,                  -- HIGH, MEDIUM, LOW
    title               TEXT NOT NULL,
    description         TEXT NOT NULL,
    file_path           TEXT NOT NULL,
    line_number         INT,
    evidence            TEXT NOT NULL,
    remediation         TEXT NOT NULL,
    is_blocker          BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rel_findings_org_repo_pr
    ON reliability_findings (organization_id, repository_id, pr_number);

CREATE INDEX IF NOT EXISTS idx_rel_findings_severity
    ON reliability_findings (organization_id, severity);

-- 3. security_assessments
CREATE TABLE IF NOT EXISTS security_assessments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,
    repository_id       UUID NOT NULL,
    analysis_id         UUID REFERENCES analyses(id) ON DELETE CASCADE,
    pr_number           INT NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    security_status     VARCHAR(64) NOT NULL,                  -- NO_SECURITY_FINDINGS, SECURITY_WARNING, SECURITY_BLOCKER, SECURITY_EVIDENCE_UNAVAILABLE
    evidence_status     VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, PARTIAL, UNAVAILABLE
    security_risk_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    critical_count      INT NOT NULL DEFAULT 0,
    high_count          INT NOT NULL DEFAULT 0,
    medium_count        INT NOT NULL DEFAULT 0,
    low_count           INT NOT NULL DEFAULT 0,
    summary             TEXT,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sec_assessments_org_repo_pr
    ON security_assessments (organization_id, repository_id, pr_number);

-- 4. reliability_assessments
CREATE TABLE IF NOT EXISTS reliability_assessments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,
    repository_id       UUID NOT NULL,
    analysis_id         UUID REFERENCES analyses(id) ON DELETE CASCADE,
    pr_number           INT NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    reliability_status  VARCHAR(64) NOT NULL,                  -- PASSED, WARNING, FAILED, PARTIAL, UNAVAILABLE
    evidence_status     VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, PARTIAL, UNAVAILABLE
    reliability_risk_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    critical_count      INT NOT NULL DEFAULT 0,
    high_count          INT NOT NULL DEFAULT 0,
    medium_count        INT NOT NULL DEFAULT 0,
    low_count           INT NOT NULL DEFAULT 0,
    summary             TEXT,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rel_assessments_org_repo_pr
    ON reliability_assessments (organization_id, repository_id, pr_number);

-- 5. Extend analyses table with unified security, reliability and risk columns
ALTER TABLE analyses
    ADD COLUMN IF NOT EXISTS security_assessment JSONB DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS reliability_assessment JSONB DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS unified_risk JSONB DEFAULT NULL;

-- 6. Extend merge_evaluations table
ALTER TABLE merge_evaluations
    ADD COLUMN IF NOT EXISTS reliability_status VARCHAR(50) DEFAULT 'AVAILABLE',
    ADD COLUMN IF NOT EXISTS unified_risk_score DOUBLE PRECISION DEFAULT 0.0;
