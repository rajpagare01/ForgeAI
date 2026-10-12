-- V1__init_testing_schema.sql
-- Testing Service Schema Migration

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. test_suites
CREATE TABLE IF NOT EXISTS test_suites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    framework VARCHAR(64) NOT NULL DEFAULT 'JUNIT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_test_suites_org_repo_name UNIQUE (organization_id, repository_id, name)
);

CREATE INDEX IF NOT EXISTS idx_suites_org ON test_suites (organization_id);

-- 2. test_cases
CREATE TABLE IF NOT EXISTS test_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    suite_id UUID NOT NULL REFERENCES test_suites(id) ON DELETE CASCADE,
    name VARCHAR(500) NOT NULL,
    classname VARCHAR(500),
    requirement_key VARCHAR(32) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_test_cases_suite_name_class UNIQUE (suite_id, name, classname)
);

CREATE INDEX IF NOT EXISTS idx_test_cases_req ON test_cases (organization_id, requirement_key);

-- 3. test_runs
CREATE TABLE IF NOT EXISTS test_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    commit_sha CHAR(40) NOT NULL,
    pull_request_number INT NULL,
    workflow_run_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'CANCELLED')),
    total_tests INT NOT NULL DEFAULT 0,
    passed_tests INT NOT NULL DEFAULT 0,
    failed_tests INT NOT NULL DEFAULT 0,
    skipped_tests INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_runs_commit ON test_runs (organization_id, commit_sha);
CREATE INDEX IF NOT EXISTS idx_runs_pr ON test_runs (organization_id, repository_id, pull_request_number);

-- 4. test_results (high-volume time indexed / partitioning ready)
CREATE TABLE IF NOT EXISTS test_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    test_run_id UUID NOT NULL REFERENCES test_runs(id) ON DELETE CASCADE,
    test_case_id UUID NOT NULL REFERENCES test_cases(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PASSED', 'FAILED', 'SKIPPED')),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    stack_trace TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_results_run ON test_results (organization_id, test_run_id, status);
CREATE INDEX IF NOT EXISTS idx_results_time ON test_results (organization_id, created_at);

-- 5. outbox_events
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_testing_outbox_unprocessed ON outbox_events (created_at) WHERE published_at IS NULL;
