-- Migration 011: Automated Repository Documentation, System Design Generator & Push Commit Guard

-- 1. generated_documents
CREATE TABLE IF NOT EXISTS generated_documents (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL,
    repository_id    UUID NOT NULL,
    doc_type         VARCHAR(32) NOT NULL,   -- OVERVIEW, API, DATA_MODEL, DEPENDENCIES, ARCHITECTURE, SYSTEM_DESIGN
    target_path      TEXT NOT NULL,          -- e.g. docs/generated/overview.md, docs/generated/system-design.md
    commit_sha       VARCHAR(64) NOT NULL,   -- code version it was built from
    content_hash     VARCHAR(64) NOT NULL,
    content_md       TEXT NOT NULL,
    citations        JSONB NOT NULL DEFAULT '[]'::jsonb,
    provenance       JSONB NOT NULL DEFAULT '{}'::jsonb,  -- model, provider, prompt_version, generator_version, fallback_used
    status           VARCHAR(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT, APPROVED, REJECTED, APPLIED, STALE, FAILED
    approved_by      TEXT,
    pr_url           TEXT,
    branch_name      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gendocs_org_repo
    ON generated_documents (organization_id, repository_id, doc_type);

CREATE UNIQUE INDEX IF NOT EXISTS uq_gendocs_version
    ON generated_documents (organization_id, repository_id, doc_type, commit_sha);

-- 2. commit_analysis_reports (Push-triggered commit intelligence & alert ledger)
CREATE TABLE IF NOT EXISTS commit_analysis_reports (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,
    repository_id       UUID NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    ref                 TEXT,
    author_name         TEXT,
    author_email        TEXT,
    commit_message      TEXT,
    status              VARCHAR(32) NOT NULL DEFAULT 'CLEAN', -- CLEAN, WARNING, CRITICAL_ALERT
    risk_score          DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    secrets_detected    INT NOT NULL DEFAULT 0,
    security_issues     INT NOT NULL DEFAULT 0,
    reliability_issues  INT NOT NULL DEFAULT 0,
    findings            JSONB NOT NULL DEFAULT '[]'::jsonb,   -- strictly redacted findings
    report_markdown     TEXT NOT NULL,
    system_design_doc_id UUID REFERENCES generated_documents(id) ON DELETE SET NULL,
    alert_sent          BOOLEAN NOT NULL DEFAULT FALSE,
    alert_recipient     TEXT,
    alert_status        VARCHAR(64) NOT NULL DEFAULT 'NONE',  -- NONE, SENT, LOGGED_DRY_RUN, FAILED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_commit_reports_org_repo
    ON commit_analysis_reports (organization_id, repository_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_commit_reports_sha
    ON commit_analysis_reports (organization_id, repository_id, commit_sha);

