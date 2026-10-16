-- Migration 010: Engineering Workflow Automation & Controlled AI Remediation Engine
-- Creates persistent storage for engineering actions, structured remediation plans,
-- patch proposals, remediation effectiveness metrics, and immutable action audit events.
-- Multi-tenant isolation enforced on every table and leading indices.

-- 1. engineering_actions
CREATE TABLE IF NOT EXISTS engineering_actions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_id           VARCHAR(128) NOT NULL UNIQUE,
    analysis_id         UUID REFERENCES analyses(id) ON DELETE CASCADE,
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    pr_number           INT NOT NULL,
    commit_sha          VARCHAR(64) NOT NULL,
    action_type         VARCHAR(64) NOT NULL,                  -- RUN_TEST, ADD_TEST, FIX_SECURITY, FIX_RELIABILITY, UPDATE_CONFIGURATION, UPDATE_DEPENDENCY, UPDATE_API_CONTRACT, DOCUMENT_CHANGE, REVIEW_REQUIRED, NO_ACTION
    title               TEXT NOT NULL,
    description         TEXT NOT NULL,
    reason              TEXT NOT NULL,
    severity            VARCHAR(32) NOT NULL,                  -- CRITICAL, HIGH, MEDIUM, LOW
    confidence          VARCHAR(32) NOT NULL,                  -- HIGH, MEDIUM, LOW
    evidence            TEXT NOT NULL,
    affected_files      JSONB NOT NULL DEFAULT '[]'::jsonb,
    affected_symbols    JSONB NOT NULL DEFAULT '[]'::jsonb,
    provenance          JSONB NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(32) NOT NULL DEFAULT 'PROPOSED', -- PROPOSED, APPROVED, REJECTED, APPLIED, FAILED, SUPERSEDED
    source_finding_id   VARCHAR(128),
    source_finding_type VARCHAR(64),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_eng_actions_org_repo_pr
    ON engineering_actions (organization_id, repository_id, pr_number);

CREATE INDEX IF NOT EXISTS idx_eng_actions_org_status
    ON engineering_actions (organization_id, status);

CREATE INDEX IF NOT EXISTS idx_eng_actions_org_action_id
    ON engineering_actions (organization_id, action_id);

CREATE INDEX IF NOT EXISTS idx_eng_actions_analysis
    ON engineering_actions (organization_id, analysis_id);

-- 2. remediation_plans
CREATE TABLE IF NOT EXISTS remediation_plans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_id           VARCHAR(128) NOT NULL REFERENCES engineering_actions(action_id) ON DELETE CASCADE,
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    title               TEXT NOT NULL,
    summary             TEXT NOT NULL,
    steps               JSONB NOT NULL DEFAULT '[]'::jsonb,    -- list of {step_number, target_file, target_symbol, change_description, validation_strategy, status}
    is_multi_repo       BOOLEAN NOT NULL DEFAULT FALSE,
    coordinated_repos   JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_impact     TEXT NOT NULL,
    provenance          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rem_plans_org_action
    ON remediation_plans (organization_id, action_id);

CREATE INDEX IF NOT EXISTS idx_rem_plans_org_repo
    ON remediation_plans (organization_id, repository_id);

-- 3. patch_proposals
CREATE TABLE IF NOT EXISTS patch_proposals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_id           VARCHAR(128) NOT NULL REFERENCES engineering_actions(action_id) ON DELETE CASCADE,
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    target_file         TEXT NOT NULL,
    target_symbol       TEXT NOT NULL,
    original_context    TEXT NOT NULL,
    proposed_replacement TEXT NOT NULL,
    unified_diff        TEXT NOT NULL,
    rationale           TEXT NOT NULL,
    source_finding      TEXT NOT NULL,
    confidence          VARCHAR(32) NOT NULL,                  -- HIGH, MEDIUM, LOW
    validation_plan     JSONB NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(32) NOT NULL DEFAULT 'PROPOSED', -- PROPOSED, VALIDATED, APPLIED, REJECTED, SUPERSEDED
    model               VARCHAR(64) NOT NULL,
    provider            VARCHAR(64) NOT NULL,
    fallback_used       BOOLEAN NOT NULL DEFAULT FALSE,
    prompt_version      VARCHAR(32) NOT NULL,
    trace_id            VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_patch_proposals_org_action
    ON patch_proposals (organization_id, action_id);

CREATE INDEX IF NOT EXISTS idx_patch_proposals_org_repo
    ON patch_proposals (organization_id, repository_id);

-- 4. remediation_effectiveness
CREATE TABLE IF NOT EXISTS remediation_effectiveness (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_id               VARCHAR(128) NOT NULL REFERENCES engineering_actions(action_id) ON DELETE CASCADE,
    organization_id         UUID NOT NULL,                     -- mandatory tenant scope
    repository_id           UUID NOT NULL,
    before_risk_score       DOUBLE PRECISION NOT NULL,
    after_risk_score        DOUBLE PRECISION NOT NULL,
    risk_delta              DOUBLE PRECISION NOT NULL,
    before_merge_readiness  VARCHAR(64) NOT NULL,
    after_merge_readiness   VARCHAR(64) NOT NULL,
    merge_readiness_delta   TEXT NOT NULL,
    findings_fixed          JSONB NOT NULL DEFAULT '[]'::jsonb,
    findings_persisted      JSONB NOT NULL DEFAULT '[]'::jsonb,
    new_findings_introduced JSONB NOT NULL DEFAULT '[]'::jsonb,
    tests_added             INT NOT NULL DEFAULT 0,
    tests_passed            BOOLEAN NOT NULL DEFAULT TRUE,
    is_effective            BOOLEAN NOT NULL DEFAULT FALSE,
    remediation_summary     TEXT NOT NULL,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rem_eff_org_action
    ON remediation_effectiveness (organization_id, action_id);

CREATE INDEX IF NOT EXISTS idx_rem_eff_org_repo
    ON remediation_effectiveness (organization_id, repository_id);

-- 5. action_events (Immutable Audit Log)
CREATE TABLE IF NOT EXISTS action_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,                         -- mandatory tenant scope
    repository_id       UUID NOT NULL,
    action_id           VARCHAR(128) NOT NULL,
    event_type          VARCHAR(64) NOT NULL,                  -- PROPOSED, APPROVED, REJECTED, VALIDATED, APPLIED, FAILED, SUPERSEDED
    actor_id            TEXT NOT NULL,                         -- developer or system identity
    details             JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id            VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_action_events_org_action
    ON action_events (organization_id, action_id);

CREATE INDEX IF NOT EXISTS idx_action_events_org_repo
    ON action_events (organization_id, repository_id);

-- 6. Extend analyses table with engineering_actions summary column
ALTER TABLE analyses
    ADD COLUMN IF NOT EXISTS engineering_actions JSONB DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS remediation_summary JSONB DEFAULT NULL;
