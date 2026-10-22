# ForgeAI Personal & Organization Workspace Architecture

## 1. Overview & Purpose

ForgeAI supports two distinct operational modes for developers and engineering teams:

1. **Organization Workspaces (`type = ORGANIZATION`)**: Multi-user team environments with Role-Based Access Control (RBAC), team hierarchies, organization-level audit logs, and shared billing/governance.
2. **Personal Workspaces (`type = PERSONAL`)**: Single-developer isolated environments allowing individual engineers to create projects, connect personal GitHub repositories, run AI-assisted analyses, and track work without requiring an organization, corporate domain, or team setup.

### The "No-Rename" Architectural Invariant
To support personal workspaces without compromising enterprise stability or breaking microservice contracts:
- **`organization_id` was NOT globally renamed to `workspace_id`.**
- Existing multi-tenant enterprise isolation, PostgreSQL Row-Level Security (RLS), RBAC policies, and internal service communication remain intact.
- A first-class `workspaces` table acts as the unified abstraction layer across `PERSONAL` and `ORGANIZATION` contexts, while services retain `organization_id` for organization-scoped entities and add nullable `workspace_id` where personal entities are supported.

---

## 2. Workspace Domain Model

### 2.1 Workspace Entities & Types

```
                    ┌─────────────────────────┐
                    │        Workspace        │
                    ├─────────────────────────┤
                    │ id: UUID (PK)           │
                    │ name: String            │
                    │ slug: String            │
                    │ type: WorkspaceType     │
                    │ ownerUserId: UUID?      │
                    │ organizationId: UUID?   │
                    │ createdAt: Instant      │
                    │ updatedAt: Instant      │
                    └───────────┬─────────────┘
                                │
               ┌────────────────┴────────────────┐
               ▼                                 ▼
      ┌─────────────────┐               ┌─────────────────┐
      │    PERSONAL     │               │  ORGANIZATION   │
      ├─────────────────┤               ├─────────────────┤
      │ owner_user_id   │               │ organization_id │
      │   NOT NULL      │               │   NOT NULL      │
      │ organization_id │               │ id = org_id     │
      │   IS NULL       │               │ owner_user_id   │
      │ 1 per user      │               │   IS NULL       │
      └─────────────────┘               └─────────────────┘
```

#### Schema Invariants (`workspaces` table in `identity-service`):
```sql
CREATE TABLE workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL,
    owner_user_id UUID,
    organization_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_workspace_type CHECK (type IN ('PERSONAL', 'ORGANIZATION')),
    CONSTRAINT chk_workspace_personal CHECK (
        type != 'PERSONAL' OR (owner_user_id IS NOT NULL AND organization_id IS NULL)
    ),
    CONSTRAINT chk_workspace_organization CHECK (
        type != 'ORGANIZATION' OR (organization_id IS NOT NULL AND id = organization_id)
    )
);

-- At most one personal workspace per user
CREATE UNIQUE INDEX idx_workspaces_personal_owner ON workspaces(owner_user_id) WHERE type = 'PERSONAL';

-- At most one organization workspace per organization
CREATE UNIQUE INDEX idx_workspaces_org_id ON workspaces(organization_id) WHERE type = 'ORGANIZATION';
```

### 2.2 Lifecycle Hooks
- **User Registration (`UserService.createUser`)**:
  - Automatically provisions a default personal workspace titled `"{username}'s Workspace"` (`slug: "{username}-personal"`).
- **Organization Creation (`OrganizationService.createOrganization`)**:
  - Automatically provisions an organization workspace with `id = organization.id`, ensuring deterministic IDs and backward compatibility for tenant resolution.

---

## 3. Authorization & Security Model

ForgeAI applies a bifurcated authorization model depending on the workspace type:

| Attribute | Personal Workspace (`PERSONAL`) | Organization Workspace (`ORGANIZATION`) |
|---|---|---|
| **Access Model** | Owner-only | Multi-role RBAC (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) |
| **Membership** | Single owner (`owner_user_id`) | Many users via `organization_memberships` |
| **Unauthorized Access** | **HTTP 403 Forbidden** (`AccessDeniedException`) | **HTTP 404 Not Found** (`ResourceNotFoundException`) to prevent cross-tenant discovery |
| **Project Creation** | `organization_id = NULL`, `workspace_id = activeWorkspace.id` | `organization_id = orgId`, `workspace_id = activeWorkspace.id` |
| **GitHub App Claiming** | Claimed to personal `workspace_id` | Claimed to `organization_id` |

### Zero-Leakage Cross-Tenant Isolation
When an authenticated user requests a resource belonging to another organization, `project-service` emits `ResourceNotFoundException` (HTTP 404). This prevents malicious actors from enumerating organization project UUIDs. When another authenticated user attempts to access a personal workspace project, `AccessDeniedException` (HTTP 403) is emitted.

---

## 4. API Gateway Boundary & Header Sanitization (`SEC-WS-01`, `SEC-WS-02`)

The API Gateway (`infrastructure/api-gateway`) is the outermost security boundary:

1. **Incoming Header Sanitization (Step 1)**:
   - Untrusted client requests are stripped of all identity, tenancy, and privilege headers:
     ```java
     httpHeaders.remove("X-User-Id");
     httpHeaders.remove("X-Org-Id");
     httpHeaders.remove("X-Organization-Id");
     httpHeaders.remove("X-User-Roles");
     httpHeaders.remove("X-Permissions");
     httpHeaders.remove("X-Workspace-Id"); // Stripped to prevent workspace spoofing
     ```
2. **JWT Verification (Step 2)**:
   - Cryptographically verifies the Bearer JWT using JWKS / RS256.
   - Extracts authentic `sub` (userId), `org_id`, `role`, and `permissions`.
3. **Route Categorization**:
   - **User-Scoped Routes** (`/api/v1/workspaces/**`, `/api/v1/organizations`, `/api/v1/users/**`): Forwarded downstream with verified `X-User-Id` without requiring an `X-Org-Id`.
   - **Org-Scoped Routes**: Validates membership via Redis cache or `identity-service`, forwarding downstream with `X-Org-Id`, `X-User-Roles`, and `X-ForgeAI-Internal: attested`.

---

## 5. Outbox Scope Partitioning (`SEC-WS-05`)

Microservices utilize the Transactional Outbox pattern for reliable event publishing. To prevent tenant-event bleeding between personal workspaces and organizations:

### Database Check Constraint:
```sql
ALTER TABLE outbox_events ADD COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'ORGANIZATION';
ALTER TABLE outbox_events ADD COLUMN workspace_id UUID;

ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_scope CHECK (
    (scope = 'ORGANIZATION' AND organization_id IS NOT NULL) OR
    (scope = 'PERSONAL' AND workspace_id IS NOT NULL AND organization_id IS NULL)
);
```

### Domain Validation:
In `OutboxService.java`:
```java
if ("ORGANIZATION".equals(scope)) {
    if (organizationId == null) {
        throw new IllegalArgumentException("ORGANIZATION scoped event requires non-null organizationId");
    }
} else if ("PERSONAL".equals(scope)) {
    if (workspaceId == null) {
        throw new IllegalArgumentException("PERSONAL scoped event requires non-null workspaceId");
    }
    if (organizationId != null) {
        throw new IllegalArgumentException("PERSONAL scoped event must have null organizationId");
    }
}
```

---

## 6. Source Control & GitHub App Integration

1. **Webhook Ingestion**:
   - Inbound GitHub webhooks (`installation.created`) are placed into `QUARANTINED` status until claimed by an authenticated user.
2. **Installation Claiming**:
   - If claiming under a Personal Workspace: The installation is bound to `workspace_id`, and `organization_id` remains `NULL`.
   - If claiming under an Organization: The installation is bound to `organization_id` (and corresponding `workspace_id`).
3. **Repository Sync**:
   - Personal repositories synchronize with `workspace_id` and `scope = 'PERSONAL'`, ensuring that personal commits and PRs do not trigger organizational audit pipelines or leak into team project boards.

---

## 7. Frontend Integration & Tenant Switching

1. **`TenantContext` (`frontend/src/lib/tenant-context.tsx`)**:
   - Maintains `activeWorkspace`, `workspaces: Workspace[]`, and helper `isPersonalWorkspace: boolean`.
   - Provides methods: `switchWorkspace(id)`, `switchToPersonalWorkspace()`, and `refreshWorkspaces()`.
2. **`apiClient` (`frontend/src/lib/api-client.tsx`)**:
   - Inspects the active workspace in storage/state:
     - If `activeWorkspace.type === 'ORGANIZATION'`, attaches `X-Org-Id: ${activeWorkspace.organizationId}`.
     - If `activeWorkspace.type === 'PERSONAL'`, omits `X-Org-Id` to prevent 403 Forbidden errors when accessing personal endpoints.
3. **`OrgSwitcher` (`frontend/src/components/navigation/OrgSwitcher.tsx`)**:
   - Distinct sections:
     - **Personal Workspace** with dedicated User Avatar and "Personal" badge.
     - **Organizations** list with role badges and active checkmarks.
     - **Create Organization** modal for seamless expansion from solo to team.
