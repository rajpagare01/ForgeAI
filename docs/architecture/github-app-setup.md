# ForgeAI — GitHub App Production Integration Guide

This guide documents the end-to-end procedure for creating, configuring, and connecting a real GitHub App with ForgeAI.

---

## 1. Create the GitHub App

1. Navigate to **GitHub Settings**:
   - For an organization: `https://github.com/organizations/<your-org>/settings/apps`
   - For a personal account: `https://github.com/settings/apps`
2. Click **New GitHub App**.
3. Fill in the App settings:
   - **GitHub App name**: `ForgeAI-<your-org>` (must be globally unique)
   - **Homepage URL**: `http://localhost:3000` (or your production domain)
   - **Callback URL**: `http://localhost:3000/api/auth/callback/github` (optional for MVP)

---

## 2. Configure Webhooks

In the **Webhook** section:
- **Active**: Checked
- **Webhook URL**: `https://<your-public-domain-or-tunnel>/api/v1/source-control/webhook`
  *(For local development, use an ngrok or Cloudflare tunnel pointing to port `8080`)*
- **Webhook secret**: Choose a strong random string (e.g., generated with `openssl rand -hex 20`).
  Save this value — it corresponds to `GITHUB_WEBHOOK_SECRET`.
- **SSL verification**: Enable SSL verification.

---

## 3. Permissions & Subscribed Events

### Repository Permissions
| Permission | Access | Purpose |
| :--- | :--- | :--- |
| **Pull requests** | Read & Write | Read PR metadata, diffs, commits; post AST analysis review comments |
| **Contents** | Read-only | Fetch files, tree structure for AST parsing |
| **Metadata** | Read-only | Authoritative repository identification (ID, name, default branch) |
| **Commit statuses** | Read & Write | Report CI test telemetry and traceability checks |

### Organization Permissions
| Permission | Access | Purpose |
| :--- | :--- | :--- |
| **Members** | Read-only | (Optional) Verify organization membership |

### Subscribe to Events
Check the following events:
- [x] **Installation** (`created`, `deleted`, `suspend`, `unsuspend`)
- [x] **Installation target**
- [x] **Pull request** (`opened`, `synchronize`, `closed`, `reopened`)
- [x] **Push**
- [x] **Check run** / **Check suite** (optional for telemetry)

---

## 4. Generate Private Key & App Credentials

1. On the App settings page, scroll down to **Private keys** and click **Generate a private key**.
2. A `.pem` file will be downloaded to your machine.
3. Note the numeric **App ID** displayed in the **About** section at the top of the App settings page.

---

## 5. Configure ForgeAI Environment Variables

Update your `.env` file (copied from `.env.example`):

```env
# Numeric GitHub App ID (from App settings page)
GITHUB_APP_ID=123456

# Shared secret configured in the Webhook section
GITHUB_WEBHOOK_SECRET=your_generated_webhook_secret

# RSA private key in PKCS#8 PEM format
# Option A: Inlined with literal newlines or escaped \n:
GITHUB_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----"

# Option B: Or path to private key file:
GITHUB_PRIVATE_KEY_PATH=/etc/forgeai/github-private-key.pem
```

---

## 6. Secure Onboarding & Installation Claim Flow

ForgeAI enforces a zero-trust tenant onboarding flow:

1. **Install the App**:
   - Go to `https://github.com/apps/<your-app-slug>` and click **Install**.
   - Select the GitHub organization and the repositories you wish to grant access to.
   - Note the numeric `installation_id` in the URL upon completion:
     `https://github.com/settings/installations/<INSTALLATION_ID>`

2. **Quarantine Phase**:
   - When GitHub delivers the `installation.created` webhook, ForgeAI receives the event with HMAC SHA-256 validation.
   - Because ForgeAI never guesses tenant IDs or generates random UUIDs, the installation is held in **Quarantine** (`status = SUSPENDED`, `orgId = 00000000-0000-0000-0000-000000000000`).

3. **Claiming the Installation**:
   - Log in to the ForgeAI dashboard as an organization owner.
   - Navigate to **Repositories** (`/repositories`).
   - Click **Claim GitHub Installation**.
   - Enter your numeric `installation_id` and submit.
   - ForgeAI calls the GitHub API using an RS256 JWT, verifies the installation exists on GitHub, and binds the installation to your authenticated ForgeAI organization.

4. **Connecting Authoritative Repositories**:
   - Once claimed, click **Connect GitHub Repo**.
   - ForgeAI queries `GET /api/v1/repositories/available` using the installation access token to list verified repositories.
   - Selecting a repository sends `POST /api/v1/repositories/connect` with `{ "fullName": "owner/repo" }`.
   - The backend retrieves authoritative metadata (GitHub ID, default branch, visibility) directly from GitHub.

---

## 7. Diagnostics & Health Verification

Verify your integration status at any time without exposing secrets:

```bash
# Gateway health check
curl http://localhost:8080/api/v1/github/health
```

Example response when configured:
```json
{
  "status": "CONFIGURED",
  "configured": true,
  "appId": 123456,
  "privateKeyPresent": true,
  "webhookSecretPresent": true,
  "totalInstallations": 1,
  "activeInstallations": 1,
  "quarantinedInstallations": 0
}
```

Example response when unconfigured:
```json
{
  "status": "UNCONFIGURED",
  "configured": false,
  "appId": null,
  "privateKeyPresent": false,
  "webhookSecretPresent": false,
  "message": "GitHub integration is not configured. Set GITHUB_APP_ID, GITHUB_PRIVATE_KEY, and GITHUB_WEBHOOK_SECRET."
}
```
