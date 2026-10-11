-- V2__fix_identity_concurrency_and_outbox.sql
-- Step 1: Concurrency fixes for refresh tokens, transactional outbox, and organization invitations

-- 1. Refresh Tokens Updates
-- Add family_id, grace_period_until, created_at
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS family_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS grace_period_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

-- Backfill family_id for existing tokens if any exist (default to id or random uuid)
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

-- Composite index on (token_hash, revoked_at) and index on family_id
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash_revoked ON refresh_tokens (token_hash, revoked_at);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id ON refresh_tokens (family_id);

-- 2. Outbox Events Table for Reliable Event Dispatch
CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB NOT NULL,
    trace_id       VARCHAR(128),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_unprocessed ON outbox_events (created_at) WHERE published_at IS NULL;

-- 3. Invitations Table for Pending Organization Joins
CREATE TABLE IF NOT EXISTS invitations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    email           VARCHAR(255) NOT NULL,
    role_id         UUID NOT NULL,
    invited_by      UUID NOT NULL,
    token           VARCHAR(255) NOT NULL UNIQUE,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_invitations_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_invitations_role FOREIGN KEY (role_id)
        REFERENCES roles (id),
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_invitations_email_org ON invitations (email, organization_id);
CREATE INDEX IF NOT EXISTS idx_invitations_token ON invitations (token);
