-- Migration 002: Durable Indexing Jobs, Provider Transparency, and Idempotency Constraints

-- 1. Extend analyses audit table for provider transparency
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS provider VARCHAR(32) NOT NULL DEFAULT 'GEMINI';
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS fallback_used BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Durable AI Indexing Jobs Table
CREATE TABLE IF NOT EXISTS ai_indexing_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    commit_sha CHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED', -- 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    last_error TEXT,
    files JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_indexing_jobs_status_created
    ON ai_indexing_jobs (status, created_at);

CREATE INDEX IF NOT EXISTS idx_ai_indexing_jobs_org_repo
    ON ai_indexing_jobs (organization_id, repository_id, commit_sha);

-- 3. Idempotency Unique Constraints
CREATE UNIQUE INDEX IF NOT EXISTS uq_code_chunks_identity
    ON code_chunks (organization_id, repository_id, commit_sha, file_path, content_hash);

CREATE UNIQUE INDEX IF NOT EXISTS uq_graph_edges_identity
    ON graph_edges (organization_id, repository_id, valid_at_sha, source_symbol, target_symbol, edge_type);
