-- V2: Identity Schema Updates based on Domain Model refinements

-- 1. Users Table Updates
ALTER TABLE users DROP CONSTRAINT uq_users_email;
ALTER TABLE users DROP CONSTRAINT uq_users_username;

ALTER TABLE users ADD COLUMN pending_email VARCHAR(255);

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));

-- 2. Teams Table Updates
ALTER TABLE teams ADD CONSTRAINT uq_teams_org_name UNIQUE (organization_id, name);

-- 3. Roles Table Updates
CREATE UNIQUE INDEX uq_roles_system_name ON roles (name) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX uq_roles_org_name ON roles (organization_id, name) WHERE organization_id IS NOT NULL;

-- 4. Team Memberships Table Updates
ALTER TABLE team_memberships ALTER COLUMN role_id DROP NOT NULL;

-- 5. Sessions Table Updates
CREATE INDEX idx_sessions_active ON sessions (user_id) WHERE revoked_at IS NULL;

-- 6. Refresh Tokens Table Updates
ALTER TABLE refresh_tokens ALTER COLUMN token_hash TYPE VARCHAR(64);
CREATE INDEX idx_rt_hash ON refresh_tokens (token_hash);

-- 7. Security Events Table Updates
ALTER TABLE security_events ALTER COLUMN details TYPE JSONB USING details::jsonb;
