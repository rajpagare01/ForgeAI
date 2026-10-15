-- Migration 006: Test Intelligence & Risk-Driven Validation Engine
-- Extends analyses table with test_strategy and test_effectiveness
-- Adds test_recommendations and test_validation_feedback tables for deterministic test prioritization and CI feedback loop

ALTER TABLE analyses ADD COLUMN IF NOT EXISTS test_strategy JSONB DEFAULT '{}'::jsonb;
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS test_effectiveness JSONB DEFAULT '{}'::jsonb;

-- 1. test_recommendations: Persisted deterministic test prioritization records
CREATE TABLE IF NOT EXISTS test_recommendations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    analysis_id UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    pr_number INT NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    test_name VARCHAR(500) NOT NULL,
    test_file VARCHAR(500) NOT NULL,
    test_method VARCHAR(500) NULL,
    target_symbol VARCHAR(500) NULL,
    priority_score NUMERIC(5,2) NOT NULL,
    priority_bucket VARCHAR(32) NOT NULL, -- 'MUST_RUN', 'SHOULD_RUN', 'OPTIONAL'
    confidence VARCHAR(16) NOT NULL, -- 'HIGH', 'MEDIUM', 'LOW'
    evidence_sources JSONB NOT NULL DEFAULT '[]'::jsonb, -- ['STATIC', 'RUNTIME', 'HISTORICAL']
    evidence_types JSONB NOT NULL DEFAULT '[]'::jsonb, -- ['AST_CALL', 'PATH_PAIRING', etc.]
    evidence_path JSONB NOT NULL DEFAULT '[]'::jsonb, -- Step-by-step change-to-test path
    reason TEXT NOT NULL,
    historical_failure_count INT NOT NULL DEFAULT 0,
    cochange_count INT NOT NULL DEFAULT 0,
    runtime_coverage_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_test_recs_org_repo_commit
    ON test_recommendations (organization_id, repository_id, commit_sha);

CREATE INDEX IF NOT EXISTS idx_test_recs_analysis
    ON test_recommendations (organization_id, analysis_id);

CREATE INDEX IF NOT EXISTS idx_test_recs_priority_bucket
    ON test_recommendations (organization_id, priority_bucket, priority_score DESC);

-- 2. test_validation_feedback: Post-PR execution feedback loop connecting predictions to actual test outcomes
CREATE TABLE IF NOT EXISTS test_validation_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    analysis_id UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    commit_sha VARCHAR(40) NOT NULL,
    workflow_run_id BIGINT NULL,
    recommended_count INT NOT NULL DEFAULT 0,
    executed_count INT NOT NULL DEFAULT 0,
    passed_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    high_priority_missed_count INT NOT NULL DEFAULT 0,
    prediction_to_failure_correlation NUMERIC(5,2) NOT NULL DEFAULT 0.0,
    test_selection_efficiency NUMERIC(5,2) NOT NULL DEFAULT 0.0,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_test_feedback_org_repo_commit
    ON test_validation_feedback (organization_id, repository_id, commit_sha);

CREATE INDEX IF NOT EXISTS idx_test_feedback_analysis
    ON test_validation_feedback (organization_id, analysis_id);
