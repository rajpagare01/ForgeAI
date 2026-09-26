-- V1: Initial Identity Schema
-- ForgeAI Identity Service - Initial Database Schema
-- This migration establishes the foundational tables for identity management.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(255) NOT NULL,
    username         VARCHAR(100) NOT NULL,
    password_hash    VARCHAR(255) NOT NULL,
    first_name       VARCHAR(100),
    last_name        VARCHAR(100),
    status           VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    email_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- ============================================================
-- ORGANIZATIONS
-- ============================================================
CREATE TABLE organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    description TEXT,
    status      VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

-- ============================================================
-- TEAMS
-- ============================================================
CREATE TABLE teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_teams_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE CASCADE
);

-- ============================================================
-- ROLES
-- ============================================================
CREATE TABLE roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID,   -- NULL = system-level role
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    scope           VARCHAR(50)  NOT NULL DEFAULT 'ORGANIZATION',
    system_defined  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_roles_organization FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE CASCADE
);

-- ============================================================
-- PERMISSIONS
-- ============================================================
CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource    VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    code        VARCHAR(200) NOT NULL,
    description TEXT,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

-- ============================================================
-- ROLE → PERMISSION (N:M join table)
-- ============================================================
CREATE TABLE role_permissions (
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles       (id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

-- ============================================================
-- ORGANIZATION MEMBERSHIPS
-- ============================================================
CREATE TABLE organization_memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    organization_id UUID NOT NULL,
    role_id         UUID NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_om_user         FOREIGN KEY (user_id)         REFERENCES users         (id) ON DELETE CASCADE,
    CONSTRAINT fk_om_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_om_role         FOREIGN KEY (role_id)         REFERENCES roles         (id),
    CONSTRAINT uq_om_user_org     UNIQUE (user_id, organization_id)
);

-- ============================================================
-- TEAM MEMBERSHIPS
-- ============================================================
CREATE TABLE team_memberships (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id    UUID NOT NULL,
    user_id    UUID NOT NULL,
    role_id    UUID NOT NULL,
    status     VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_tm_team    FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tm_user    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_tm_role    FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT uq_tm_team_user UNIQUE (team_id, user_id)
);

-- ============================================================
-- SESSIONS
-- ============================================================
CREATE TABLE sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    metadata     JSONB,
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- ============================================================
-- REFRESH TOKENS
-- ============================================================
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID NOT NULL,
    token_hash   VARCHAR(512) NOT NULL,
    issued_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    replaced_by  UUID,   -- FK to self for token rotation chain
    CONSTRAINT fk_rt_session     FOREIGN KEY (session_id)  REFERENCES sessions      (id) ON DELETE CASCADE,
    CONSTRAINT fk_rt_replaced_by FOREIGN KEY (replaced_by) REFERENCES refresh_tokens(id)
);

-- ============================================================
-- SECURITY EVENTS
-- ============================================================
CREATE TABLE security_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID,   -- Nullable (e.g., failed login attempts before user lookup)
    organization_id UUID,   -- Nullable (org-level events)
    event_type      VARCHAR(100) NOT NULL,
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    details         TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_se_user         FOREIGN KEY (user_id)         REFERENCES users         (id) ON DELETE SET NULL,
    CONSTRAINT fk_se_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE SET NULL
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX idx_org_memberships_user         ON organization_memberships (user_id);
CREATE INDEX idx_org_memberships_organization ON organization_memberships (organization_id);
CREATE INDEX idx_team_memberships_user        ON team_memberships (user_id);
CREATE INDEX idx_team_memberships_team        ON team_memberships (team_id);
CREATE INDEX idx_sessions_user                ON sessions (user_id);
CREATE INDEX idx_refresh_tokens_session       ON refresh_tokens (session_id);
CREATE INDEX idx_security_events_user         ON security_events (user_id);
CREATE INDEX idx_security_events_created_at   ON security_events (created_at);
