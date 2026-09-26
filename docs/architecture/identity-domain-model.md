# ForgeAI Identity Service — Domain Model & Architecture

## 1. Purpose

The Identity Service is the foundational trust anchor of ForgeAI. It is the **only** service that knows who a user is, what organizations they belong to, and what roles they hold. All other services (Project, Source Control, Testing, AI) consume the user UUID and organization UUID from access tokens — they never query the Identity Service database directly.

**Identity Service owns:**
- User identity (who you are)
- Organizations and teams (where you belong)
- Roles and permissions (what you can do — RBAC)
- Sessions and tokens (how you authenticate)
- Security audit events (what happened)

**Identity Service does NOT own:**
- Projects, tasks, sprints
- GitHub repositories, commits, pull requests
- Test runs or test reports
- AI conversations or knowledge graphs

---

## 2. Domain Entities

### User
Represents a ForgeAI account — the human identity.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Immutable; primary key |
| `email` | String (Email VO) | Unique (case-insensitive); verified address |
| `pendingEmail` | String | New email awaiting verification; null if no change in flight |
| `username` | String (Username VO) | Unique (case-insensitive policy); 3–39 chars |
| `passwordHash` | String | BCrypt output |
| `firstName` | String | Optional |
| `lastName` | String | Optional |
| `status` | UserStatus | ACTIVE \| SUSPENDED \| LOCKED \| DEACTIVATED |
| `emailVerified` | boolean | True when current `email` is confirmed |
| `createdAt` | Instant | Immutable after creation |
| `updatedAt` | Instant | Updated on any change |

**UserStatus Lifecycle:**

| Status | Auth | Sessions | Notes |
|---|---|---|---|
| `ACTIVE` | ✅ | Active | Normal state |
| `SUSPENDED` | ❌ | Revoked on suspend | Admin-imposed; reversible |
| `LOCKED` | ❌ | Remain but blocked | Auto-lock (e.g., brute-force); unlockable |
| `DEACTIVATED` | ❌ | Revoked | User/admin initiated; soft-delete equivalent |

### Organization
Represents a ForgeAI tenant/workspace.

| Field | Notes |
|---|---|
| `id` | UUID; immutable |
| `name` | Display name |
| `slug` | URL-safe unique identifier |
| `description` | Optional |
| `status` | ACTIVE \| INACTIVE \| SUSPENDED |

**No `owner_id` field.** Ownership is expressed through `OrganizationMembership` with the `OWNER` role.

### OrganizationMembership
The many-to-many join between User and Organization, with a role assignment.

| Field | Notes |
|---|---|
| `id` | UUID |
| `userId` | FK → User |
| `organizationId` | FK → Organization |
| `roleId` | FK → Role (org-scoped) |
| `status` | ACTIVE \| PENDING \| INACTIVE |

`(userId, organizationId)` must be unique.

### Team
A sub-group within one Organization.

| Field | Notes |
|---|---|
| `id` | UUID |
| `organizationId` | FK → Organization (immutable) |
| `name` | Unique within the organization |
| `description` | Optional |

### TeamMembership
The many-to-many join between User and Team, with an optional role assignment.

| Field | Notes |
|---|---|
| `id` | UUID |
| `teamId` | FK → Team |
| `userId` | FK → User |
| `roleId` | FK → Role (team-scoped); **nullable** |
| `status` | ACTIVE \| INACTIVE |

`(teamId, userId)` must be unique. Team roles cannot grant organization-wide permissions.

### Role
A named bundle of permissions.

| Field | Notes |
|---|---|
| `id` | UUID |
| `organizationId` | NULL for system roles; UUID for custom org roles |
| `name` | Unique per scope (see Role Uniqueness) |
| `description` | Optional |
| `scope` | ORGANIZATION \| TEAM |
| `systemDefined` | `true` = platform-managed; cannot be modified by orgs |

**Initial system roles:** OWNER, ADMIN, PROJECT_MANAGER, DEVELOPER, TESTER, VIEWER, TEAM_LEAD, TEAM_MEMBER

### Permission
An atomic capability, expressed as `RESOURCE_ACTION`.

| Field | Notes |
|---|---|
| `id` | UUID |
| `resource` | e.g., `PROJECT` |
| `action` | e.g., `CREATE` |
| `code` | Unique; e.g., `PROJECT_CREATE` |
| `description` | Optional |

**Initial permission codes (non-exhaustive):**
```
PROJECT_READ, PROJECT_CREATE, PROJECT_UPDATE, PROJECT_DELETE
TASK_READ, TASK_CREATE, TASK_UPDATE, TASK_DELETE
REPOSITORY_READ, REPOSITORY_CONNECT
TEST_READ, TEST_RUN
AI_CHAT, AI_ANALYZE
MEMBER_INVITE, MEMBER_REMOVE, ROLE_ASSIGN
```

### Session
An authenticated device/browser session.

| Field | Notes |
|---|---|
| `id` | UUID |
| `userId` | FK → User |
| `createdAt` | Session start |
| `lastUsedAt` | Updated on each token refresh |
| `expiresAt` | Absolute session expiry |
| `revokedAt` | NULL = active; set on logout or account lock |
| `metadata` | JSONB; device/client hints (non-sensitive) |

### RefreshToken
A secure, rotatable credential for session renewal.

**Key design: only the SHA-256 hash is stored — the raw token is given to the client once and never persisted.**

| Field | Notes |
|---|---|
| `id` | UUID |
| `sessionId` | FK → Session |
| `tokenHash` | `SHA-256(raw_token)` as hex |
| `issuedAt` | When this token was created |
| `expiresAt` | Absolute expiry |
| `revokedAt` | NULL = valid; set on rotation or revocation |
| `replacedBy` | UUID of successor token (rotation chain) |

### SecurityEvent
Immutable audit record. Append-only. Never modified or deleted.

| Field | Notes |
|---|---|
| `id` | UUID |
| `userId` | Nullable FK → User |
| `organizationId` | Nullable FK → Organization |
| `eventType` | String code; e.g., `LOGIN_SUCCESS` |
| `ipAddress` | IPv4 or IPv6 |
| `userAgent` | Browser/client user-agent |
| `details` | JSONB; structured context (no PII/credentials) |
| `createdAt` | Immutable timestamp |

---

## 3. Entity Relationships

```
User
 ├── OrganizationMembership ──► Organization
 │        └── Role (org-scoped)
 │
 ├── TeamMembership ──────────► Team ──► Organization
 │        └── Role (team-scoped, optional)
 │
 ├── Session ─────────────────► RefreshToken (chain via replaced_by)
 │
 └── SecurityEvent (userId nullable)

Organization ──► SecurityEvent (organizationId nullable)

Role ──► RolePermission ──► Permission
```

**Cardinalities:**
- User : OrganizationMembership = 1:N
- Organization : OrganizationMembership = 1:N
- Organization : Team = 1:N
- User : TeamMembership = 1:N
- Team : TeamMembership = 1:N
- Role : RolePermission = 1:N
- Permission : RolePermission = 1:N
- User : Session = 1:N
- Session : RefreshToken = 1:N

---

## 4. Domain Invariants

1. `user.id` is set at creation and **never changes**.
2. `user.email` is unique across all users (case-insensitive comparison).
3. `user.username` is unique; normalized to lowercase for comparison.
4. Email change requires verification — `pendingEmail` holds the new address; `email` remains active until confirmed.
5. A SUSPENDED, LOCKED, or DEACTIVATED user cannot authenticate.
6. Disabling a user account **must** revoke all their active sessions.
7. `(userId, organizationId)` is unique in `organization_memberships`.
8. Every active organization must have at least one `OrganizationMembership` with the OWNER role and ACTIVE status.
9. The last active OWNER cannot leave or be downgraded without first transferring ownership.
10. `(userId, teamId)` is unique in `team_memberships`.
11. A team belongs to exactly one organization (`team.organizationId` is immutable).
12. Team-scoped roles cannot grant organization-wide permissions.
13. `(roleId, permissionId)` is unique in `role_permissions`.
14. System-defined roles cannot be modified or deleted by organizations.
15. `security_events` records are **immutable** — no updates, no deletes.

---

## 5. RBAC Design

**Structure:**
```
User
 └── OrganizationMembership
          └── Role
               └── Permissions (via RolePermission)
```

**Role scopes prevent privilege escalation:** An OWNER of a team (TEAM scope) cannot perform ORGANIZATION-scoped operations without having an org-level role assignment.

**Role uniqueness:**
- System roles: name unique globally (where `organization_id IS NULL`)
- Org custom roles: name unique within their organization (where `organization_id IS NOT NULL`)

---

## 6. Multi-Tenancy Model

ForgeAI uses explicit, organization-scoped multi-tenancy:

```
User A
 ├── Organization X → DEVELOPER role
 ├── Organization Y → PROJECT_MANAGER role
 └── Organization Z → OWNER role
```

Every resource in other services (projects, test runs, etc.) will be scoped to an `organization_id`. Other services receive the user UUID and organization UUID from the access token issued by Identity Service — they never cross-query Identity's database.

---

## 7. Organization Ownership

**Design decision: No `owner_id` column on `organizations`.**

Ownership is expressed through:

```
Organization ← OrganizationMembership (role = OWNER, status = ACTIVE) → User
```

This supports multiple owners. The invariant "at least one active owner exists" is enforced by application logic in a dedicated `OrganizationOwnershipPolicy` domain service. A database-level deferred constraint is intentionally avoided because it complicates batch membership operations.

---

## 8. Session & Token Model

Tokens are not stored raw. Only a `SHA-256` hash is persisted. If the database is compromised, tokens cannot be replayed.

Refresh token rotation creates a chain: each token's `replaced_by` points to its successor. Presenting a revoked token triggers reuse detection and revokes the entire session.

---

## 9. Database Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Email uniqueness | `CREATE UNIQUE INDEX ON users (lower(email))` | Case-insensitive without CITEXT extension |
| Username uniqueness | `CREATE UNIQUE INDEX ON users (lower(username))` | Consistent with email approach |
| Team name uniqueness | `UNIQUE(organization_id, name)` on teams | Names unique within org, not globally |
| Role name uniqueness | Two partial unique indexes | System vs org-defined role namespace isolation |
| Token storage | Hash only (`SHA-256`) | Security: breach cannot replay tokens |
| `details` in security_events | `JSONB` (not TEXT) | Structured audit metadata querying |
| Team `role_id` | Nullable | Not all team memberships need a specific role |
| Ownership | Application-enforced invariant | DB constraints are too restrictive for multi-owner scenarios |

---

## 10. Deletion / Lifecycle Strategy

| Entity | Strategy |
|---|---|
| User | **Soft-delete** via `status = DEACTIVATED` |
| Organization | **Soft-delete** via `status = INACTIVE` |
| Team | **Hard delete** (cascade to memberships) |
| OrganizationMembership | `status = INACTIVE` preferred; hard-delete acceptable |
| TeamMembership | **Hard delete** |
| Role (system) | **Never deleted** |
| Role (org custom) | **Hard delete** only if no active memberships reference it |
| Permission | **Never deleted** |
| Session | Mark `revoked_at`; hard-delete after TTL |
| RefreshToken | Mark `revoked_at`; hard-delete after TTL |
| SecurityEvent | **Never deleted**; archived after retention period |
