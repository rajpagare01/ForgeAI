-- Migration 003: Idempotent Indexing Jobs Identity Constraint
-- Enforces UNIQUE(organization_id, repository_id, commit_sha) on ai_indexing_jobs

-- 1. Safely deduplicate historical rows deterministically:
-- Retain the job with highest priority status (COMPLETED > PROCESSING > QUEUED > FAILED),
-- breaking ties with the most recent updated_at / created_at.
DELETE FROM ai_indexing_jobs
WHERE id NOT IN (
    SELECT DISTINCT ON (organization_id, repository_id, commit_sha) id
    FROM ai_indexing_jobs
    ORDER BY organization_id, repository_id, commit_sha,
        CASE status
            WHEN 'COMPLETED' THEN 1
            WHEN 'PROCESSING' THEN 2
            WHEN 'QUEUED' THEN 3
            ELSE 4
        END,
        updated_at DESC,
        created_at DESC
);

-- 2. Create the unique constraint/index for organization + repository + commit
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_indexing_jobs_identity
    ON ai_indexing_jobs (organization_id, repository_id, commit_sha);
