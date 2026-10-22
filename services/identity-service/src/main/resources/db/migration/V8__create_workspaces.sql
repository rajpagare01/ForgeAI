-- V8: Workspaces Model & Personal Workspace Support
-- ForgeAI Identity Service
-- Supports PERSONAL and ORGANIZATION workspaces with strict database-backed uniqueness guarantees

CREATE TABLE IF NOT EXISTS workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL,
    owner_user_id UUID NOT NULL,
    organization_id UUID NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_workspaces_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspaces_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT chk_workspaces_type_enum CHECK (type IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT chk_workspaces_type CHECK (
        (type = 'PERSONAL' AND organization_id IS NULL AND owner_user_id IS NOT NULL) OR
        (type = 'ORGANIZATION' AND organization_id IS NOT NULL)
    )
);

-- Unique index: At most ONE personal workspace per user (database-enforced uniqueness)
CREATE UNIQUE INDEX IF NOT EXISTS uq_workspaces_personal_owner 
    ON workspaces (owner_user_id) 
    WHERE type = 'PERSONAL';

-- Unique index: At most ONE workspace per organization
CREATE UNIQUE INDEX IF NOT EXISTS uq_workspaces_organization 
    ON workspaces (organization_id) 
    WHERE type = 'ORGANIZATION';

-- Performance and lookup indexes
CREATE INDEX IF NOT EXISTS idx_workspaces_owner ON workspaces (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_workspaces_org ON workspaces (organization_id);
CREATE INDEX IF NOT EXISTS idx_workspaces_type_owner ON workspaces (type, owner_user_id);

-- Idempotent deterministic backfill for existing users
INSERT INTO workspaces (id, type, owner_user_id, organization_id, name, created_at, updated_at)
SELECT gen_random_uuid(), 'PERSONAL', u.id, NULL, COALESCE(u.username || '''s Workspace', 'Personal Workspace'), NOW(), NOW()
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM workspaces w WHERE w.type = 'PERSONAL' AND w.owner_user_id = u.id
);

-- Idempotent deterministic backfill for existing organizations
INSERT INTO workspaces (id, type, owner_user_id, organization_id, name, created_at, updated_at)
SELECT o.id, 'ORGANIZATION',
       COALESCE(
           (SELECT om.user_id FROM organization_memberships om WHERE om.organization_id = o.id ORDER BY om.created_at ASC LIMIT 1),
           (SELECT u.id FROM users u ORDER BY u.created_at ASC LIMIT 1)
       ),
       o.id, o.name, o.created_at, o.updated_at
FROM organizations o
WHERE NOT EXISTS (
    SELECT 1 FROM workspaces w WHERE w.type = 'ORGANIZATION' AND w.organization_id = o.id
);
