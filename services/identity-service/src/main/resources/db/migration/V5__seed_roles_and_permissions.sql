-- V3: Seed System Roles and Permissions

-- 1. Insert Core Permissions
INSERT INTO permissions (id, resource, action, code, description) VALUES
(gen_random_uuid(), 'ORGANIZATION', 'UPDATE', 'ORGANIZATION_UPDATE', 'Update organization details'),
(gen_random_uuid(), 'ORGANIZATION', 'DELETE', 'ORGANIZATION_DELETE', 'Deactivate organization'),
(gen_random_uuid(), 'MEMBER', 'INVITE', 'MEMBER_INVITE', 'Invite new members to the organization'),
(gen_random_uuid(), 'MEMBER', 'REMOVE', 'MEMBER_REMOVE', 'Remove members from the organization'),
(gen_random_uuid(), 'ROLE', 'ASSIGN', 'ROLE_ASSIGN', 'Assign roles to members'),
(gen_random_uuid(), 'ROLE', 'ADMINISTER', 'ROLE_ADMINISTER', 'Create, update, and delete roles'),
(gen_random_uuid(), 'TEAM', 'CREATE', 'TEAM_CREATE', 'Create new teams'),
(gen_random_uuid(), 'TEAM', 'UPDATE', 'TEAM_UPDATE', 'Update team details'),
(gen_random_uuid(), 'TEAM', 'DELETE', 'TEAM_DELETE', 'Delete teams'),
(gen_random_uuid(), 'TEAM_MEMBER', 'ADD', 'TEAM_MEMBER_ADD', 'Add members to a team'),
(gen_random_uuid(), 'TEAM_MEMBER', 'REMOVE', 'TEAM_MEMBER_REMOVE', 'Remove members from a team'),
(gen_random_uuid(), 'PROJECT', 'READ', 'PROJECT_READ', 'View projects'),
(gen_random_uuid(), 'PROJECT', 'CREATE', 'PROJECT_CREATE', 'Create projects'),
(gen_random_uuid(), 'PROJECT', 'UPDATE', 'PROJECT_UPDATE', 'Update projects'),
(gen_random_uuid(), 'PROJECT', 'DELETE', 'PROJECT_DELETE', 'Delete projects');

-- 2. Insert System Roles
INSERT INTO roles (id, organization_id, name, description, scope, system_defined) VALUES
(gen_random_uuid(), NULL, 'OWNER', 'Organization Owner with full access', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'ADMIN', 'Organization Administrator', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'PROJECT_MANAGER', 'Project Manager', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'DEVELOPER', 'Developer', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'TESTER', 'QA Tester', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'VIEWER', 'Read-only Viewer', 'ORGANIZATION', true),
(gen_random_uuid(), NULL, 'TEAM_LEAD', 'Team Lead', 'TEAM', true),
(gen_random_uuid(), NULL, 'TEAM_MEMBER', 'Team Member', 'TEAM', true);

-- 3. Map Permissions to System Roles

-- OWNER gets everything
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'OWNER';

-- ADMIN gets most organization-wide things except ORGANIZATION_DELETE
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p 
WHERE r.name = 'ADMIN' AND p.code != 'ORGANIZATION_DELETE';

-- TEAM_LEAD gets team management capabilities (TEAM scoped)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p 
WHERE r.name = 'TEAM_LEAD' AND p.code IN ('TEAM_UPDATE', 'TEAM_MEMBER_ADD', 'TEAM_MEMBER_REMOVE', 'PROJECT_READ', 'PROJECT_CREATE', 'PROJECT_UPDATE');

-- TEAM_MEMBER gets basic team capabilities
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p 
WHERE r.name = 'TEAM_MEMBER' AND p.code IN ('PROJECT_READ', 'PROJECT_UPDATE');
