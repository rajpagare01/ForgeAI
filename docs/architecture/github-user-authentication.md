# ForgeAI — GitHub User Authentication & Identity Integration Architecture

This document describes the architectural design, security model, and operational workflow for **GitHub User Authentication and Identity Integration** in ForgeAI.

---

## 1. Architectural Overview & System Separation

ForgeAI strictly distinguishes between two complementary GitHub integration models:

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                           GitHub User Authentication                            │
│                        (User-to-Server / Developer Identity)                   │
│                                                                                │
│   Developer clicks "Continue with GitHub"                                      │
│          ↓                                                                     │
│   Identity Service exchanges OAuth code                                        │
│          ↓                                                                     │
│   user_github_identities (github_user_id BIGINT UNIQUE, AES-256-GCM token)     │
│          ↓                                                                     │
│   ForgeAI User Account + Session JWT                                           │
│          ↓                                                                     │
│   Guaranteed Personal Workspace (type=PERSONAL, owner_user_id=user.id)        │
└──────────────────────────────────────┬─────────────────────────────────────────┘
                                       │ Realizes Developer Context
                                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                         GitHub App Installation & Sync                         │
│                    (Server-to-Server / Repository Automation)                  │
│                                                                                │
│   Organization installs ForgeAI GitHub App                                     │
│          ↓                                                                     │
│   github_installations (quarantine/claim flow with HMAC-SHA256 webhooks)       │
│          ↓                                                                     │
│   Repositories synchronized into Workspaces (Personal or Organization)         │
│          ↓                                                                     │
│   Projects, Requirements Traceability, AST Analysis & PR Security Scans        │
└────────────────────────────────────────────────────────────────────────────────┘
```

### Invariants Preserved
1. **Source of Truth**: ForgeAI remains the authoritative session provider. The browser receives and uses a ForgeAI JWT signed with the Identity Service RSA private key (`/.well-known/jwks.json`). The GitHub access token is **never** used as a frontend session token.
2. **Coexistence**: The existing GitHub App installation architecture (`source-control-service`), webhooks, repository sync, and AST PR analysis operate without disruption.
3. **Stable Identity Key**: GitHub accounts are identified by their immutable numeric `github_user_id` (`BIGINT`), with a database unique constraint preventing collisions or takeovers if a user renames their GitHub handle.
4. **Zero Raw Secret Exposure**: `GITHUB_CLIENT_SECRET` is used exclusively server-side. GitHub access tokens are encrypted with AES-256-GCM before database persistence and are never returned in any client-facing DTO, log, or downstream service call.

---

## 2. End-to-End Authentication & Provisioning Flow

```text
[Frontend Browser]             [Edge API Gateway]           [Identity Service]           [GitHub API]
        │                              │                            │                         │
   1. Click "Continue with GitHub"     │                            │                         │
        ├──────────────────────────────┼───────────────────────────>│                         │
        │ GET /api/v1/auth/github/start│                            │                         │
        │                              │                     Generate 32-byte                 │
        │                              │                     cryptographic state              │
        │                              │                     Persist in oauth_states          │
        │<─────────────────────────────┼────────────────────────────┤                         │
        │ 2. Return authorizeUrl       │                            │                         │
        │                              │                            │                         │
   3. Redirect to GitHub               │                            │                         │
        ├──────────────────────────────┼────────────────────────────┼────────────────────────>│
        │                              │                            │                         │
   4. User consents & GitHub redirects │                            │                         │
        │ with code & state            │                            │                         │
        │<─────────────────────────────┼────────────────────────────┼─────────────────────────┤
        │                              │                            │                         │
   5. POST /api/v1/auth/github/callback│                            │                         │
        ├──────────────────────────────┼───────────────────────────>│                         │
        │ { code, state, redirectUri } │                            │                         │
        │                              │                     Validate state                   │
        │                              │                     (check expiration & replay)      │
        │                              │                     Mark state.used = true           │
        │                              │                            │                         │
        │                              │                            │ 6. Exchange code        │
        │                              │                            ├────────────────────────>│
        │                              │                            │ POST /oauth/access_token│
        │                              │                            │<────────────────────────┤
        │                              │                            │ 7. Return access_token  │
        │                              │                            │                         │
        │                              │                            │ 8. Fetch User Profile   │
        │                              │                            ├────────────────────────>│
        │                              │                            │ GET /user & /user/emails│
        │                              │                            │<────────────────────────┤
        │                              │                            │ Return profile data     │
        │                              │                            │                         │
        │                              │                     Encrypt access_token             │
        │                              │                     (AES-256-GCM)                    │
        │                              │                     Find / create ForgeAI User       │
        │                              │                     Provision Personal Workspace     │
        │                              │                     Generate ForgeAI JWT & Session   │
        │<─────────────────────────────┼────────────────────────────┤                         │
        │ 9. Return ForgeAI JWT        │                            │                         │
        │    & User Session            │                            │                         │
```

---

## 3. Database Schema & Migration (Flyway V10)

The schema additions in `services/identity-service/src/main/resources/db/migration/V10__add_github_user_identities.sql`:

```sql
-- 1. Linked GitHub User Identities
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

-- 2. Anti-CSRF Replay Protection Table
CREATE TABLE IF NOT EXISTS oauth_states (
    state VARCHAR(128) PRIMARY KEY,
    user_id UUID NULL,
    redirect_url VARCHAR(1024),
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 4. Security Controls & Protections

### A. Anti-CSRF & Replay Defense
- Every authorization request generates a 32-byte `SecureRandom` token stored in `oauth_states`.
- TTL: 10 minutes.
- When `/callback` or `/link` executes, the state record is fetched. If missing, expired, or previously flagged `used=true`, the transaction immediately aborts with an authentication error.
- State consumption is marked within the database transaction before code exchange.

### B. Account Takeover & Email Safety
- Accounts are **never** silently merged based on unverified email strings.
- Email verification flags returned by GitHub (`primary=true, verified=true`) are verified before associating emails.
- If an email matches an existing local user who has not connected GitHub, the user must authenticate first and execute the explicit account link workflow from settings.

### C. Personal Workspace Invariant
- Every newly created GitHub developer account immediately receives a **Personal Workspace**:
  - `type = PERSONAL`
  - `owner_user_id = user.id`
  - `organization_id = null`
- Guaranteed idempotent by `WorkspaceService.getOrCreatePersonalWorkspace(...)`.

### D. Disconnect & Lockout Protection
- Users can unlink GitHub from their account at any time without deleting user or workspace data.
- **Lockout Prevention Guard**: If a user registered exclusively via GitHub OAuth and has never configured a local password, disconnecting GitHub is blocked with an explicit directive: `"Cannot disconnect GitHub: You must set an account password in Settings before disconnecting GitHub to ensure you are not locked out."`

---

## 5. API Endpoints Specification

All endpoints are hosted at `/api/v1/auth/github`:

| Method | Endpoint | Auth Required | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/config` | No | Returns `{ configured: boolean, clientId: string }` |
| `GET` | `/start` | Optional | Generates state and returns GitHub authorize URL |
| `POST` | `/callback` | No | Exchanges code, signs in or provisions user, returns JWT session |
| `POST` | `/link` | Yes | Links GitHub account to current authenticated user |
| `POST` | `/disconnect` | Yes | Unlinks GitHub account with lockout protection |
| `GET` | `/status` | Yes | Returns `{ linked: boolean, identity: GitHubIdentityDto }` |

---

## 6. Configuration & Environment Variables

| Variable | Service | Default / Example | Purpose |
| :--- | :--- | :--- | :--- |
| `GITHUB_CLIENT_ID` | `identity-service` | `Iv1.xxxxxxxxxxxx` | OAuth Client ID from GitHub App |
| `GITHUB_CLIENT_SECRET` | `identity-service` | `secret_xxxxxxxx` | OAuth Client Secret from GitHub App |
| `GITHUB_REDIRECT_URI` | `identity-service` | `http://localhost:3000/auth/github/callback` | Authorized OAuth redirect URL |
| `GITHUB_TOKEN_ENCRYPTION_KEY` | `identity-service` | derived from `JWT_SECRET_KEY` | 256-bit AES-GCM encryption key |
