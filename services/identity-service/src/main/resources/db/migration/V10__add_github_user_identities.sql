-- V10__add_github_user_identities.sql
-- ForgeAI Identity Service: GitHub User Authentication & Identity Integration
-- Preserves existing workspace, organization, and tenant ownership invariants.

-- 1. Table for GitHub User Identities linked to ForgeAI Users
CREATE TABLE IF NOT EXISTS user_github_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    github_user_id BIGINT NOT NULL,
    github_login VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    email VARCHAR(255),
    avatar_url VARCHAR(1024),
    profile_url VARCHAR(1024),
    encrypted_access_token TEXT,
    token_type VARCHAR(64),
    scope VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_github_identities_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_github_user_id UNIQUE (github_user_id),
    CONSTRAINT uq_user_github_link UNIQUE (user_id)
);

-- Indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_user_github_identities_user_id ON user_github_identities (user_id);
CREATE INDEX IF NOT EXISTS idx_user_github_identities_github_user_id ON user_github_identities (github_user_id);
CREATE INDEX IF NOT EXISTS idx_user_github_identities_login ON user_github_identities (github_login);

-- 2. Table for OAuth States (Cryptographically secure anti-CSRF & replay protection)
CREATE TABLE IF NOT EXISTS oauth_states (
    state VARCHAR(128) PRIMARY KEY,
    user_id UUID NULL,
    redirect_url VARCHAR(1024),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oauth_states_expires ON oauth_states (expires_at, used);
