-- Migration 007: Automated PR Review & Merge Readiness Engine
-- Extends analyses with merge readiness decision, review findings, and persists audit evaluations.

ALTER TABLE analyses
ADD COLUMN IF NOT EXISTS merge_readiness JSONB DEFAULT NULL,
ADD COLUMN IF NOT EXISTS review_findings JSONB DEFAULT '[]'::jsonb;

-- Create merge_evaluations table for permanent, immutable audit records
CREATE TABLE IF NOT EXISTS merge_evaluations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id UUID REFERENCES analyses(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    pr_number INT NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    decision VARCHAR(50) NOT NULL,            -- READY, READY_WITH_WARNINGS, NOT_READY, BLOCKED_BY_MISSING_EVIDENCE
    readiness_score DOUBLE PRECISION NOT NULL, -- 0.0 to 100.0
    confidence VARCHAR(20) NOT NULL,          -- HIGH, MEDIUM, LOW
    policy_version VARCHAR(50) NOT NULL,      -- e.g. merge-policy-v1
    blocking_reasons TEXT[] DEFAULT '{}',
    warnings TEXT[] DEFAULT '{}',
    required_actions TEXT[] DEFAULT '{}',
    required_checks JSONB DEFAULT '[]'::jsonb,
    required_tests JSONB DEFAULT '[]'::jsonb,
    security_status VARCHAR(50) DEFAULT 'SECURITY_EVIDENCE_UNAVAILABLE',
    github_mode VARCHAR(50) DEFAULT 'MODE_A_ADVISORY',
    decision_timestamp TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_merge_evaluations_repo_pr ON merge_evaluations(organization_id, repository_id, pr_number);
CREATE INDEX IF NOT EXISTS idx_merge_evaluations_commit ON merge_evaluations(organization_id, repository_id, commit_sha);
