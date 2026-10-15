-- Migration 004: Incremental Repository Intelligence & Current-State Tracking
-- Adds is_current flag to code_chunks and graph_edges to separate CURRENT repository state from HISTORICAL commit/PR evidence.

-- 1. Code Chunks current-state tracking
ALTER TABLE code_chunks 
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_code_chunks_org_repo_current 
    ON code_chunks (organization_id, repository_id, is_current);

-- 2. Knowledge Graph Edges current-state tracking
ALTER TABLE graph_edges 
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_graph_edges_org_repo_current 
    ON graph_edges (organization_id, repository_id, is_current);
