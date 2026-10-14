-- V2__workflow_run_fields.sql
-- Adds GitHub workflow_run metadata columns to test_runs.
-- The existing `status` column tracks the ForgeAI CI test result status (RUNNING/PASSED/FAILED/CANCELLED).
-- New github_run_status / github_run_conclusion columns capture the raw GitHub workflow lifecycle.

-- Add GitHub workflow lifecycle columns (all nullable so existing rows are unaffected)
ALTER TABLE test_runs
    ADD COLUMN IF NOT EXISTS workflow_name     VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS run_number        INTEGER      NULL,
    ADD COLUMN IF NOT EXISTS github_run_status VARCHAR(32)  NULL,
    ADD COLUMN IF NOT EXISTS conclusion        VARCHAR(32)  NULL,
    ADD COLUMN IF NOT EXISTS head_branch       VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS trigger_event     VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS html_url          TEXT         NULL,
    ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ  NULL;

-- Unique constraint: one ForgeAI TestRun record per (repository, github workflow_run id).
-- Enables upsert-style reconciliation when successive workflow_run events arrive
-- (queued → in_progress → completed) for the same GitHub run.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_test_runs_repo_workflow_run'
    ) THEN
        ALTER TABLE test_runs
            ADD CONSTRAINT uq_test_runs_repo_workflow_run
                UNIQUE (repository_id, workflow_run_id);
    END IF;
END $$;


-- Index to support lookups by workflow_run_id within an organisation
CREATE INDEX IF NOT EXISTS idx_runs_workflow_run
    ON test_runs (organization_id, repository_id, workflow_run_id);
