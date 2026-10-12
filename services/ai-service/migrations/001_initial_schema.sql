-- Initial AI Service Schema for ForgeAI
-- Consolidates pgvector semantic retrieval and recursive knowledge graph tables into PostgreSQL (ai_db)

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Code Chunks for Semantic Code Search
CREATE TABLE IF NOT EXISTS code_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    commit_sha CHAR(40) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    symbol_name VARCHAR(255),
    symbol_type VARCHAR(64), -- 'CLASS', 'FUNCTION', 'METHOD', 'MODULE'
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indices for code_chunks
-- HNSW index for fast approximate nearest neighbor search under cosine metric
CREATE INDEX IF NOT EXISTS idx_code_chunks_embedding 
    ON code_chunks USING hnsw (embedding vector_cosine_ops);

-- Composite B-tree index leading with organization_id to enforce strict tenant isolation
CREATE INDEX IF NOT EXISTS idx_code_chunks_org_repo_commit 
    ON code_chunks (organization_id, repository_id, commit_sha);

CREATE INDEX IF NOT EXISTS idx_code_chunks_hash 
    ON code_chunks (organization_id, content_hash);

-- 2. Knowledge Graph Edges (PostgreSQL-based Recursive Knowledge Graph)
CREATE TABLE IF NOT EXISTS graph_edges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    source_symbol VARCHAR(500) NOT NULL,
    target_symbol VARCHAR(500) NOT NULL,
    edge_type VARCHAR(64) NOT NULL, -- 'CALLS', 'IMPORTS', 'EXTENDS'
    valid_at_sha CHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indices for multi-hop graph traversal and impact CTE queries
CREATE INDEX IF NOT EXISTS idx_graph_edges_source 
    ON graph_edges (organization_id, repository_id, source_symbol);

CREATE INDEX IF NOT EXISTS idx_graph_edges_target 
    ON graph_edges (organization_id, repository_id, target_symbol);

-- 3. AI Analysis Audit Records
CREATE TABLE IF NOT EXISTS analyses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL,
    entity_type VARCHAR(32) NOT NULL, -- 'PR', 'TASK', 'REPO'
    entity_id VARCHAR(64) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    risk_level VARCHAR(32), -- 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'
    retrieved_chunks JSONB,
    execution_duration_ms BIGINT NOT NULL DEFAULT 0,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analyses_org_entity 
    ON analyses (organization_id, entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_analyses_trace_id 
    ON analyses (trace_id);

