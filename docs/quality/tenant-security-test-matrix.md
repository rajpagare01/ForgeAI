# ForgeAI Tenant Security Test Matrix

> **Status:** ACTIVE  
> **Audited Baseline:** 269 Automated Tests + Live Runtime Verification  
> **Scope:** Multi-Tenant Isolation, Personal Workspace Autonomy, OAuth/GitHub App Security, AI Boundaries

---

## 1. Adversarial Attack vs. Expected Rejection Matrix

This matrix specifies the security behaviors, expected HTTP response codes, and test verifications across all adversarial attack vectors.

| Attack / Access Vector | Pre-Condition | Injected Vector / Malicious Action | Expected Rejection / Response | Security Invariant Protected | Automated Test Suite & Method |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Cross-Tenant Repository Read** | Attacker has valid JWT in Org/Ws A | Requests `GET /api/v1/repositories/{id}` where `{id}` belongs to Org/Ws B | **`404 Not Found`** | Anti-Enumeration (Attacker cannot confirm repo exists) | [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)`#testCrossTenantRepositoryAccessReturnsNotFound` |
| **Workspace Header Spoofing** | Attacker has valid JWT for User B | Injects `X-Workspace-Id: {user_a_workspace_id}` | **`403 Forbidden`** | Workspace Ownership Binding (Cannot masquerade as another user's workspace) | [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)`#testWorkspaceSpoofingBlocked` & `scratch/verify_live_github_flow.cjs` (Step 7c) |
| **Personal Workspace Query without Org ID** | Authenticated User in Personal Workspace | Queries `GET /api/v1/repositories?workspaceId={id}` with no `organizationId` | **`200 OK`** | Personal Workspace Autonomy (No organization required for personal repos) | [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)`#testPersonalWorkspaceListRepositories` & `scratch/verify_live_github_flow.cjs` (Steps 4 & 5) |
| **External Header Spoofing (Gateway Boundary)** | Unattested External Client | Injects `X-ForgeAI-Internal: attested`, `X-Org-Id: ...`, `X-User-Id: ...` | **Headers Stripped / Neutralized** | Gateway Border Defense (Internal identity headers must originate from Gateway) | [`GatewayTenantFilterTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/infrastructure/api-gateway/src/test/java/com/forgeai/gateway/filter/GatewayTenantFilterTest.java)`#testClientInjectedInternalHeadersStripped` & [`TenantContextFilterTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/infrastructure/security/TenantContextFilterTest.java) |
| **Unauthenticated Installation Start** | Unauthenticated Caller (No JWT) | Calls `POST /api/v1/github/installation-start` | **`401 Unauthorized`** | Installation Binding Authentication (Only verified users can generate state) | [`GatewayTenantFilterTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/infrastructure/api-gateway/src/test/java/com/forgeai/gateway/filter/GatewayTenantFilterTest.java)`#testProtectedRoutesRequireJwt` |
| **Installation Callback Replay Attack** | Malicious Actor captures consumed callback URL | Replays `GET /api/v1/github/installation-callback?installation_id=...&state={consumed_token}` | **`302 Redirect` with error message** | Single-Use State Security (Replay attack blocked, token destroyed on first use) | [`GitHubInstallationLifecycleSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/GitHubInstallationLifecycleSecurityTest.java)`#testReplayStateTokenRejected` & `scratch/verify_live_github_flow.cjs` (Step 6) |
| **Malformed / Missing Callback Params** | Browser redirects from GitHub with missing params | Calls `GET /api/v1/github/installation-callback` with empty query string | **`302 Redirect` with error message** | Graceful Error Handling (Browser redirected cleanly to `/repositories?error=...`) | [`GitHubInstallationLifecycleSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/GitHubInstallationLifecycleSecurityTest.java)`#testMissingCallbackParamsRedirects` & `scratch/verify_live_github_flow.cjs` (Step 1) |
| **Cross-Tenant Project Access** | Attacker has valid JWT in Org/Ws A | Requests `GET /api/v1/projects/{id}` where `{id}` belongs to Org/Ws B | **`404 Not Found`** | Project Isolation (Prevents discovery of foreign project IDs) | [`ProjectWorkspaceSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/project-service/src/test/java/com/forgeai/project/ProjectWorkspaceSecurityTest.java)`#testCrossTenantProjectAccessReturnsNotFound` |
| **Cross-Tenant Task Access** | Attacker has valid JWT in Org/Ws A | Requests `GET /api/v1/projects/{projId}/tasks/{taskId}` belonging to Org/Ws B | **`404 Not Found`** | Task & Sprint Isolation | [`ProjectWorkspaceSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/project-service/src/test/java/com/forgeai/project/ProjectWorkspaceSecurityTest.java)`#testCrossTenantTaskAccessReturnsNotFound` |
| **Cross-Tenant Test Run Access** | Attacker has valid JWT in Org/Ws A | Requests `GET /api/v1/test-runs/{id}` belonging to Org/Ws B | **`404 Not Found`** | CI Testing Isolation (Test results cannot be enumerated across tenants) | [`TestingWorkspaceSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/testing-service/src/test/java/com/forgeai/testing/TestingWorkspaceSecurityTest.java)`#testCrossTenantTestRunAccessReturnsNotFound` |
| **Cross-Tenant AI Semantic Vector Search** | Attacker queries PR risk or embeddings | Requests vector similarity matching for repository belonging to Org/Ws B | **`0 Results / 404`** | Vector Space Partitioning (pgvector queries filtered by tenant context) | `services/ai-service/tests/test_pr_risk_service.py` & `test_embeddings.py` |
| **Duplicate Webhook Delivery** | GitHub Webhook delivery retry | Sends identical webhook payload twice with same `X-GitHub-Delivery` GUID | **`200 OK` (Processed once)** | Webhook Idempotency (Outbox & event processing prevents duplicate executions) | [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)`#testDuplicateWebhookDeliveryIdempotent` |
| **Unsigned / Forged Webhook Payload** | Malicious Actor posts fake push event | Sends fake commit event to `POST /api/v1/webhooks/github` with forged HMAC | **`401 Unauthorized`** | Cryptographic Webhook Authentication (Validates `X-Hub-Signature-256`) | [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)`#testInvalidWebhookSignatureRejected` |

---

## 2. Code Coverage & Test Suite Mapping

### 2.1 Java Microservice Security Test Suites
- [`SourceControlWorkspaceSecurityE2ETest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/SourceControlWorkspaceSecurityE2ETest.java)
  - Covers: Cross-tenant repository read (`404`), Personal workspace repository creation & retrieval, Scope swapping attack prevention, Outbox workspace scoping, Duplicate webhook deduplication.
- [`GitHubInstallationLifecycleSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/GitHubInstallationLifecycleSecurityTest.java)
  - Covers: Cryptographic state generation, Single-use state verification, Anti-replay defense, Safe callback redirect on missing parameters, Multi-user installation claiming isolation.
- [`TenantContextFilterTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/source-control-service/src/test/java/com/forgeai/sourcecontrol/infrastructure/security/TenantContextFilterTest.java)
  - Covers: Gateway attestation marker enforcement (`X-ForgeAI-Internal: attested`), Unattested caller header neutralization, Scope parsing.
- [`ProjectWorkspaceSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/project-service/src/test/java/com/forgeai/project/ProjectWorkspaceSecurityTest.java)
  - Covers: Cross-tenant project read (`404`), Cross-tenant task creation/read (`404`), Personal workspace project management.
- [`TestingWorkspaceSecurityTest.java`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/services/testing-service/src/test/java/com/forgeai/testing/TestingWorkspaceSecurityTest.java)
  - Covers: Cross-tenant test run read (`404`), Personal workspace test ingestion, Traceability matrix scoping.

### 2.2 Python AI Service Test Suites
- `services/ai-service/tests/test_pr_risk_service.py`: PR risk evaluation partitioned by tenant and repository.
- `services/ai-service/tests/test_reliability_service.py`: Flaky test prediction and failure correlation scoping.
- `services/ai-service/tests/test_security_service.py`: Static analysis risk detection boundary checks.
- `services/ai-service/tests/test_embeddings.py`: Vector cosine similarity search partitioned by tenant context.

### 2.3 Live Acceptance Test Script
- [`scratch/verify_live_github_flow.cjs`](file:///c:/Users/asus/OneDrive/Desktop/Desktop%201/7th%20sem/major/ForgeAI/scratch/verify_live_github_flow.cjs)
  - Executes live adversarial tests against `http://localhost:8080`:
    - Public callback without JWT (302 Found)
    - Installation initiation (Cryptographic state)
    - Personal workspace repository listing (200 OK without Org ID)
    - Callback execution & Anti-replay defense (Replay blocked)
    - Multi-user isolation (User B cannot see User A repos, 404 on cross-repo access, 403 on spoofed workspace header).
