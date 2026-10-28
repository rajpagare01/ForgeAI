# ForgeAI — Re-Audit Remediation Ledger

**Audit Baseline Date:** 2026-10-28  
**Branch:** `main`  
**Initial Commit:** `7cd80b6`  
**Ledger Status:** ACTIVE REMEDIATION  

This document tracks all confirmed findings identified during the re-audit of the ForgeAI platform.

---

## P0 / Critical Security & Data Integrity

### GH-BUG-01 — GitHub OAuth Callback Race & Session Hydration Mismatch
- **Severity:** P0 / Critical
- **Status:** CONFIRMED
- **Root Cause:** In `frontend/src/app/auth/github/callback/page.tsx`, `if (isAuthenticated)` evaluates to `false` on initial mount before `AuthProvider` finishes hydrating `localStorage` tokens. An existing authenticated ForgeAI user performing a GitHub account link is routed into `loginWithGitHub()` instead of `linkGitHubAccount()`. Additionally, if linking fails or throws, catch logic fell back to checking `localStorage` and falsely reporting success, and redirects went to `/` instead of `/repositories`.
- **Files Changed:**
  - `frontend/src/app/auth/github/callback/page.tsx`
  - `frontend/src/lib/auth-context.tsx`
- **Regression Test:** `frontend/src/__tests__/auth-callback.test.tsx`
- **Runtime Verification:** Browser OAuth callback simulation with existing vs unauthenticated session state.
- **Evidence:** Source inspection lines 28–96 of `page.tsx`.

---

### GH-BUG-02 — Personal-Scoped RepositoryConnectedEvent Outbox Failure
- **Severity:** P0 / Critical
- **Status:** CONFIRMED
- **Root Cause:** `RepositoryConnectedEvent` builder calls in `InstallationEventHandler.java:210`, `GitHubInstallationDiscoveryService.java:473`, and `InstallationReconciliationService.java:139` omitted `.workspaceId(repo.getWorkspaceId())`. For personal repositories where `organizationId` is `null`, `getWorkspaceId()` returned `null`, violating `OutboxService` validation (`Personal-scoped domain event REPOSITORY_CONNECTED requires a non-null workspaceId`).
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/handler/InstallationEventHandler.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/GitHubInstallationDiscoveryService.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/InstallationReconciliationService.java`
- **Regression Test:** `services/source-control-service/src/test/java/com/forgeai/sourcecontrol/outbox/OutboxScopePartitioningTest.java`
- **Runtime Verification:** Personal repository connection via webhook and discovery outbox publishing.
- **Evidence:** Confirmed missing `.workspaceId()` in all 3 discovery/reconciliation event builders.

---

### GH-BUG-03 — Installation Claim and Associate Ownership Bypass
- **Severity:** P0 / Critical
- **Status:** CONFIRMED
- **Root Cause:** In `GitHubInstallationDiscoveryService.java` and `RepositoryApplicationService.java`, installation claiming permitted personal workspace claiming without verifying that the caller's linked GitHub account matches the installation's GitHub account login/ID. Furthermore, personal re-association allowed reassignment when `claimedByUserId` was not populated.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/GitHubInstallationDiscoveryService.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/RepositoryApplicationService.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/presentation/controller/InstallationController.java`
- **Regression Test:** `services/source-control-service/src/test/java/com/forgeai/sourcecontrol/GitHubInstallationSecurityTest.java`
- **Runtime Verification:** Attempt claim from User B for User A's installation -> 403 Forbidden.
- **Evidence:** Verified lines 477–530 of `RepositoryApplicationService.java` lacked identity check.

---

### GH-BUG-04 — PermitAll X-User-Id Header Spoofing on GitHub Auth Endpoints
- **Severity:** P0 / Critical
- **Status:** CONFIRMED
- **Root Cause:** In `GitHubAuthController.java`, permitAll endpoints (`/start`, `/link`, `/disconnect`, `/status`) trusted the inbound `X-User-Id` header without requiring and validating `X-ForgeAI-Internal: attested`. External untrusted requests could spoof another user's UUID.
- **Files Changed:**
  - `services/identity-service/src/main/java/com/forgeai/identity/api/controller/GitHubAuthController.java`
- **Regression Test:** `services/identity-service/src/test/java/com/forgeai/identity/security/GitHubAuthControllerSecurityTest.java`
- **Runtime Verification:** Direct curl to `/api/v1/auth/github/status` with spoofed `X-User-Id` and no JWT -> 401/403.
- **Evidence:** Inspected `GitHubAuthController.java:147-151`.

---

### PROJ-BUG-01 — WorkspaceJpaEntity Generated ID Overwrite on Org Workspaces
- **Severity:** P0 / Critical
- **Status:** CONFIRMED
- **Root Cause:** `WorkspaceJpaEntity.java` in `identity-service` and `source-control-service` used `@GeneratedValue(strategy = GenerationType.UUID)` on `id`. For organization workspaces, `id` MUST equal `organization_id` (enforced by DB constraint `V9__workspace_integrity_constraints.sql`). `@GeneratedValue` can overwrite pre-assigned IDs on persist.
- **Files Changed:**
  - `services/identity-service/src/main/java/com/forgeai/identity/infrastructure/adapter/out/persistence/entity/WorkspaceJpaEntity.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/persistence/entity/WorkspaceJpaEntity.java`
- **Regression Test:** Unit tests asserting explicit workspace ID preservation during persist.
- **Runtime Verification:** Organization workspace creation where `workspace.id == organization.id`.
- **Evidence:** Verified `@GeneratedValue` present in identity and source-control entity models.

---

## P1 / High Priority Defect Remediations

### PROJ-BUG-02 — Read-Only Transaction Failure in listProjects Auto-Provisioning
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** In `ProjectApplicationService.java:179`, `listProjects()` was annotated with `@Transactional(readOnly = true)`. On lines 185–198, if the workspace record did not exist, it attempted `workspaceRepository.save(autoWs)`, which triggers an `INSERT` statement in a PostgreSQL read-only transaction, failing with a `PSQLException`.
- **Files Changed:**
  - `services/project-service/src/main/java/com/forgeai/project/application/service/ProjectApplicationService.java`
- **Regression Test:** `ProjectApplicationServiceTest.java`
- **Runtime Verification:** Call `GET /api/v1/projects` when workspace entity is not yet mirrored in project-service DB.
- **Evidence:** Inspected `ProjectApplicationService.java:179-198`.

---

### BUG-08 — AI Insights Test Validation Request & Response DTO Alignment
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `TestValidationResponse` in `services/ai-service/app/presentation/dtos.py` placed metrics inside `metrics: Dict[str, Any]`, while `ai-insights/page.tsx` read root-level fields `prediction_to_failure_correlation`, `test_selection_efficiency`, and `high_priority_missed_count`. Also `handleValidateTests` risked sending null `analysis_id`.
- **Files Changed:**
  - `services/ai-service/app/presentation/dtos.py`
  - `frontend/src/app/(dashboard)/ai-insights/page.tsx`
- **Regression Test:** `pytest services/ai-service/tests` & frontend build.
- **Runtime Verification:** Trigger Post-PR Test Validation in AI Insights UI.
- **Evidence:** Source comparison between `dtos.py` and `page.tsx:3681-4137`.

---

### BUG-10 — AI Engineering Actions & Remediation Summary Not Populated / Composite PR ID
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `AnalysisResponseDTO` and `AnalysisRouter` did not return structured `engineering_actions` and `remediation_summary` objects expected by the frontend. Additionally, composite PR IDs (`<repo_uuid>#<pr_number>`) failed when entity_id parser expected pure integers.
- **Files Changed:**
  - `services/ai-service/app/presentation/dtos.py`
  - `services/ai-service/app/presentation/router.py`
  - `services/ai-service/app/domain/models.py`
- **Regression Test:** `services/ai-service/tests/test_router.py`
- **Runtime Verification:** GET `/api/v1/ai/analyses/{entity_type}/{entity_id}` with composite PR ID.
- **Evidence:** Verified DTO definitions and router parsing.

---

### BUG-12 — GitHub Account Linking Swallowed as False Success
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** Linked to GH-BUG-01. The catch block in `frontend/src/app/auth/github/callback/page.tsx` checked `savedToken` and treated fatal OAuth errors as success if an existing token was in storage.
- **Files Changed:**
  - `frontend/src/app/auth/github/callback/page.tsx`
- **Regression Test:** Frontend callback mock test for failed link.
- **Runtime Verification:** Attempt invalid code exchange when logged in -> surfaces error toast, no false redirect.
- **Evidence:** Lines 85–96 in `callback/page.tsx`.

---

### BUG-15 — Traceability Spine Flow Merge Decision Field & Risk Score Discrepancies
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** Frontend `TraceabilitySpineFlow.tsx` expected `decision` while backend sent `status` or vice versa; risk score scale varied between 0..100 and 0.0..1.0 across components.
- **Files Changed:**
  - `frontend/src/components/traceability/TraceabilitySpineFlow.tsx`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/TraceabilityAggregationService.java`
- **Regression Test:** Traceability flow unit test.
- **Runtime Verification:** Load traceability spine in frontend; verify node badge colors and risk percentages.
- **Evidence:** Inspected field names in `TraceabilitySpineFlow.tsx`.

---

### BUG-16 — API Gateway Operations Route Precedence for Audit Events
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** In API Gateway `application.yml`, a generic wildcard route matched before `/api/v1/operations/audit-events`, incorrectly routing audit queries to identity or project service instead of source-control-service.
- **Files Changed:**
  - `services/api-gateway/src/main/resources/application.yml`
- **Regression Test:** Gateway route routing test.
- **Runtime Verification:** `GET /api/v1/operations/audit-events` through gateway returns 200 from source-control.
- **Evidence:** Gateway route order inspection.

---

### BUG-17 — Production Docker Compose Bind Mount Elimination
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** Host class directories and workspace source volumes were mounted in production/test compose files, causing VirtioFS lockup and drift.
- **Files Changed:**
  - `docker-compose.prod.yml`
  - `docker-compose.dev.yml`
- **Regression Test:** Docker compose validation.
- **Runtime Verification:** Container starts cleanly without host class mounts.
- **Evidence:** Successfully eliminated host classes mount in `docker-compose.dev.yml`.

---

### BUG-19 — AI Insights Frontend Property Mismatches
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** Frontend AI Insights accessed properties `factor`, `points`, `target_repository_name`, `downstream_ci_status`, `recent_modifications_count` that differed from AI service output schema (`title`, `weight`, `repository_name`, etc.).
- **Files Changed:**
  - `frontend/src/app/(dashboard)/ai-insights/page.tsx`
- **Regression Test:** `npm run build` in frontend.
- **Runtime Verification:** All tabs in AI Insights render without runtime undefined property errors.
- **Evidence:** Component rendering inspection in `page.tsx`.

---

### BUG-21 — Review Finding Missing Provenance Source
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `ReviewFinding` in `services/ai-service/app/domain/models.py` and `dtos.py` lacked the `provenance_source` field required for auditability.
- **Files Changed:**
  - `services/ai-service/app/domain/models.py`
  - `services/ai-service/app/presentation/dtos.py`
- **Regression Test:** Pytest review findings model test.
- **Runtime Verification:** Analysis results include `provenance_source`.
- **Evidence:** Inspected `models.py`.

---

### BUG-24 — Task Assignee Input & Project Slug Normalization in Frontend
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** In `frontend/src/app/(dashboard)/projects/[projectId]/page.tsx`, task assignment expected user UUID while input accepted raw username string without resolution, and project slugs were not normalized to lowercase kebab-case.
- **Files Changed:**
  - `frontend/src/app/(dashboard)/projects/[projectId]/page.tsx`
- **Regression Test:** Frontend test for task creation.
- **Runtime Verification:** Create project and task; verify slug and assignee persistence.
- **Evidence:** Inspected task creation modal in `projects/[projectId]/page.tsx`.

---

### NEW-BUG-04 / GH-BUG-07 (App Slug) — Hardcoded GitHub App Slug Fallback forgeai-raj-dev
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `GitHubAppConfig.java` defaulted app slug to developer-specific `forgeai-raj-dev` instead of standard configurable environment variable `GITHUB_APP_SLUG`.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/config/GitHubAppConfig.java`
- **Regression Test:** `GitHubAppConfigTest.java`
- **Runtime Verification:** Verify app slug defaults cleanly to `forgeai` or configured environment value.
- **Evidence:** Line in `GitHubAppConfig.java` containing `forgeai-raj-dev`.

---

### GH-BUG-07 (Identity URL) — Hardcoded Identity Service URL / Accept Header
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `IdentityServiceClient.java` had fallback URL hardcoded to `http://localhost:8081` even in container environments where service discovery should use `IDENTITY_SERVICE_URL`. Also Accept headers lacked standard JSON specification.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/client/IdentityServiceClient.java`
- **Regression Test:** Identity service client integration test.
- **Runtime Verification:** Source control resolves user GitHub profile across Docker network.
- **Evidence:** Inspected `IdentityServiceClient.java`.

---

### GH-BUG-08 — GitHubAppClient Missing 404 Optional Handling
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** `GitHubAppClient.java` threw raw `HttpClientErrorException` on 404 instead of catching and returning `Optional.empty()` or null, causing cascading 500s when querying non-existent installations or repositories.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/github/GitHubAppClient.java`
- **Regression Test:** `GitHubAppClientTest.java`
- **Runtime Verification:** Call repository details for non-existent repo -> returns 404 cleanly.
- **Evidence:** Inspected `GitHubAppClient.java`.

---

### GH-BUG-05 — Repository Discovery Deduplication, Deterministic Ordering, and Quarantine Handling
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** When multiple installations exist or repositories are discovered concurrently, duplicate repo records or non-deterministic ordering occurred. Quarantined records were not consistently isolated from public tenant listing.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/GitHubInstallationDiscoveryService.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/persistence/repository/RepositoryRepository.java`
- **Regression Test:** Repository discovery pagination and deduplication tests.
- **Runtime Verification:** List repositories across multiple installations with stable pagination.
- **Evidence:** Inspected query and sync logic.

---

### GH-BUG-06 — OAuth Setup URL Handling and IllegalStateException Mapping
- **Severity:** P1 / High
- **Status:** CONFIRMED
- **Root Cause:** GitHub App setup redirect without an OAuth state or when unconfigured threw `IllegalStateException` resulting in HTTP 500 instead of a structured HTTP 400/409 with an actionable error response.
- **Files Changed:**
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/presentation/controller/GitHubConnectionController.java`
  - `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/presentation/GlobalExceptionHandler.java`
- **Regression Test:** Controller test for unconfigured or missing state.
- **Runtime Verification:** Access setup URL directly without state -> clean redirect to frontend with error param.
- **Evidence:** Inspected error handlers.

