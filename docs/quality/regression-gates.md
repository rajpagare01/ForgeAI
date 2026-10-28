# ForgeAI Regression Gates & Controlled Feature Policy

> **Status:** ACTIVE  
> **Enforcement Level:** MANDATORY CI & ARCHITECTURAL REVIEW GATE  
> **Applies to:** All PRs, service additions, endpoints, database migrations, and frontend modifications.

---

## 1. Controlled Development Policy

With the Enterprise Audit baseline frozen and verified green (see [`docs/quality/enterprise-audit-baseline.md`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/docs/quality/enterprise-audit-baseline.md)), ForgeAI is now in **Controlled Feature Development Mode**.

Broad architectural refactoring, arbitrary security model alterations, and bypasses of tenant isolation are prohibited. Every proposed change must pass the eight mandatory regression gates (Gates A through H) before merging.

---

## 2. Mandatory Regression Gates (A – H)

### Gate A: Multi-Tenant Isolation Gate
* **Rule:** Every domain entity, data store query, event outbox message, and API endpoint must be scoped by the caller's verified tenant context (`organizationId` or `workspaceId`).
* **Constraints:**
  1. The API Gateway strips all client-supplied identity headers (`X-User-Id`, `X-Org-Id`, `X-Workspace-Id`, `X-Workspace-Scope`, `X-ForgeAI-Internal`) before rebuilding attested headers from verified JWT claims.
  2. Downstream services accept tenant identity headers only when signed or attested by the Edge Gateway (`X-ForgeAI-Internal: attested`).
  3. Attempting to access a non-existent or foreign repository, project, or task must return `404 Not Found` rather than `403 Forbidden` to prevent identifier enumeration.
  4. Attempting to access a resource with a spoofed workspace header that does not belong to the authenticated user must return `403 Forbidden`.
* **Prohibited:**
  - Database queries without `WHERE organization_id = ?` or `WHERE workspace_id = ?`.
  - Re-introducing global, unscoped lookups by raw ID alone.

---

### Gate B: Personal Workspace Invariant Gate
* **Rule:** Personal workspaces operate autonomously and must never require an `organizationId`.
* **Constraints:**
  1. Personal workspace repositories, PRs, commits, branches, and test runs are scoped by `workspaceId` with `scope = 'PERSONAL'`.
  2. In Personal Workspace mode, the frontend suppresses `X-Org-Id` and injects `X-Workspace-Id` along with `X-Workspace-Scope: PERSONAL`.
  3. Ownership of the personal workspace is bound to the authenticated user's ID (`workspace.getOwnerUserId().equals(callerUserId)`). Non-owners must receive `403 Forbidden` (`GitHubAccessDeniedException`).
* **Prohibited:**
  - Throwing `IllegalArgumentException("Organization ID must be provided via tenant context")` when `workspaceId` is present in personal scope.
  - Forcing users to create or join an organization to connect GitHub repositories.

---

### Gate C: GitHub Installation & App Flow Gate
* **Rule:** GitHub App connections must use the secure, unified single-click lifecycle with cryptographic state.
* **Constraints:**
  1. Generating an installation flow (`POST /api/v1/github/installation-start`) requires an authenticated JWT identity.
  2. Installation `state` tokens must be cryptographically generated random strings (256-bit entropy) with a strict TTL (10 minutes) stored in Redis or database.
  3. State tokens are single-use only. Once verified or consumed during callback, replaying the same state token must be rejected immediately.
  4. The browser installation callback route (`GET /api/v1/github/installation-callback`) is public at the Gateway to allow GitHub browser redirects, while verifying the cryptographic state token before binding the installation.
  5. Missing required callback parameters (`installation_id`, `state`) must redirect safely to `/repositories?error=...` rather than throwing an unhandled exception or 500 error.
* **Prohibited:**
  - Manual entry or copy-pasting of GitHub `installation_id`.
  - Reusable or predictable state tokens.
  - Associating an installation with the wrong workspace or tenant.

---

### Gate D: Repository Discovery Gate
* **Rule:** Repository discovery must reflect authentic live GitHub App permissions without fabrication.
* **Constraints:**
  1. All repository discovery calls (`GET /api/v1/repositories/available`) must query GitHub's authenticated API using valid GitHub App installation tokens.
  2. Repositories discovered must distinguish between public and private visibility accurately.
  3. Discovery queries are strictly bound to the active workspace's associated GitHub installation.
* **Prohibited:**
  - Returning hardcoded or mock repository listings in production environments.
  - Leaking repositories belonging to another GitHub installation or tenant.

---

### Gate E: AI Tenant Security Gate
* **Rule:** AI intelligence engines, embeddings, and analytics must respect tenant boundaries and never execute unauthorized writes.
* **Constraints:**
  1. AI service endpoints (`/api/v1/ai/**`) must enforce `organizationId` or `workspaceId` on every request.
  2. All vector embeddings (`pr_embeddings`, `task_embeddings`) in pgvector must include tenant context filters in cosine/L2 distance search queries (`WHERE repository_id = ?` or `WHERE tenant_id = ?`).
  3. AI analysis results (PR risk scores, reliability intelligence, test blast radius) are read-only insights and cannot directly mutate code or merge PRs.
* **Prohibited:**
  - Cross-tenant semantic search or global vector similarity queries.
  - Direct write access from AI background workers without human or system policy authorization.

---

### Gate F: Credential & Secret Management Gate
* **Rule:** Zero hardcoded credentials, tokens, or private keys anywhere in the repository.
* **Constraints:**
  1. GitHub App private keys (`.pem`), client secrets, JWT RSA keys, and database passwords must be loaded strictly from environment variables or secret vaults.
  2. Sensitive headers (`Authorization`, `Cookie`) and tokens must be redacted in logs and error responses.
  3. Commit pre-hooks and CI scans must reject any committed keys or tokens.
* **Prohibited:**
  - Committing `.pem`, `.key`, `.env`, or credential tokens to git.
  - Logging raw GitHub App private keys or Bearer JWT tokens in application logs.

---

### Gate G: Webhook Verification Gate
* **Rule:** Inbound webhooks must be cryptographically authenticated and processed idempotently.
* **Constraints:**
  1. GitHub webhooks (`POST /api/v1/webhooks/github`) must verify the `X-Hub-Signature-256` HMAC using the configured webhook secret.
  2. Webhook payloads must be processed idempotently using the GitHub delivery GUID (`X-GitHub-Delivery`) to prevent duplicate processing on retries.
  3. Webhook processing failures must not leak internal stack traces or database errors to GitHub.
* **Prohibited:**
  - Processing unsigned or invalidly signed webhooks.
  - Re-executing non-idempotent actions upon duplicate webhook delivery.

---

### Gate H: Database Migration & Schema Gate
* **Rule:** Database migrations must be forward-compatible, reversible, and validated against entity mappings.
* **Constraints:**
  1. Flyway migration scripts must be sequentially numbered (e.g., `V4__...`, `V5__...`) and tested on both fresh initialization and existing databases.
  2. All new tables and columns must include appropriate foreign keys, indices, and tenant columns (`organization_id` nullable, `workspace_id` not null or indexed, `scope` column).
  3. Hibernate `ddl-auto: validate` must pass without `SchemaManagementException`.
* **Prohibited:**
  - Breaking schema changes without migration scripts.
  - Omitting tenant or workspace foreign key constraints on multi-tenant tables.

---

## 3. Feature Development Verification Checklist

Before opening a pull request or merging a feature, developers and reviewers must answer all 11 invariant questions:

1. [ ] Does this feature support both **Personal Workspace** (no `organizationId`) and **Organization Workspace**?
2. [ ] Does the endpoint reject cross-tenant access with **`404 Not Found`** for repository lookups (preventing enumeration)?
3. [ ] Does the endpoint reject spoofed workspace headers with **`403 Forbidden`**?
4. [ ] Does the feature preserve the single-click GitHub App connection flow with cryptographic state?
5. [ ] Does the feature avoid any manual entry of `installation_id` or repository IDs?
6. [ ] Are all database queries and vector searches scoped by `organizationId` or `workspaceId`?
7. [ ] Are all GitHub API calls authenticated using real App installation tokens?
8. [ ] Are all sensitive keys, tokens, and secrets loaded via environment variables?
9. [ ] Does the feature include automated tests covering both valid access and unauthorized cross-tenant attacks?
10. [ ] Do all 269 baseline regression tests pass without failure?
11. [ ] Does the live runtime verification script pass on local Docker?
