# Security: Secret & Credential Scanning Specification

## 1. Overview

ForgeAI includes an embedded, high-throughput secret detection engine capable of analyzing repository snapshots, pull request patches, and ad-hoc code snippets without external network dependencies.

The core mandate of the Secret Scanning system is **Zero Raw Secret Exposure**: sensitive keys, tokens, and credentials must never be exposed, stored unredacted, or leaked into client interfaces, telemetry, or logs.

---

## 2. Supported Credential Signatures

ForgeAI uses deterministic regular expressions and entropy heuristics to detect known secret formats across all text files and source code:

| Rule ID | Category | Signature Pattern Description | Severity |
| :--- | :--- | :--- | :--- |
| `SEC-SECRET-001` | **AWS Access Key ID** | `AKIA[0-9A-Z]{16}` | `CRITICAL` |
| `SEC-SECRET-002` | **GitHub Personal Access Token** | `ghp_[0-9a-zA-Z]{36}`, `gho_[0-9a-zA-Z]{36}` | `CRITICAL` |
| `SEC-SECRET-003` | **GitHub Fine-Grained PAT** | `github_pat_[0-9a-zA-Z_]{22,}` | `CRITICAL` |
| `SEC-SECRET-004` | **Stripe Secret / Restricted Key** | `sk_live_[0-9a-zA-Z_]{16,}`, `rk_live_[0-9a-zA-Z_]{16,}` | `CRITICAL` |
| `SEC-SECRET-005` | **Private Cryptographic Keys** | `-----BEGIN (RSA\|EC\|DSA\|OPENSSH) PRIVATE KEY-----` | `CRITICAL` |
| `SEC-SECRET-006` | **JSON Web Tokens (JWT)** | `eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}` | `HIGH` |
| `SEC-SECRET-007` | **Generic Hardcoded Passwords** | `(?:password\|passwd\|secret\|token\|api_key)\s*[:=]\s*['"][^'"]{8,}['"]` | `HIGH` |

---

## 3. Zero Raw Secret Exposure Protocol

To safeguard credential integrity across the entire ingestion, scanning, transport, and visualization lifecycle, the following protocols are strictly enforced:

### 3.1 Backend Ingestion & Detection
1. **In-Memory Matching**: Files are scanned in memory without temporary disk spills.
2. **Immediate Redaction**: When a regex match occurs, the raw match is immediately replaced with a cryptographic redaction mask:
   ```python
   redacted = f"***[REDACTED_LEN_{len(raw_token)}]"
   ```
3. **No Unredacted Logging**: Logger formatters filter stdout/stderr for credential patterns.

### 3.2 Safe Code Context Extraction
When a finding is displayed in the developer UI (`/ai-insights`), code surrounding the finding is presented to provide debugging context while guaranteeing security:
- **Line Window**: The viewer displays a controlled window of 7 lines ($[\text{target} - 3, \text{target} + 3]$).
- **Target Line Indication**: The target line is highlighted for developer convenience.
- **Client-Side Re-Sanitization**: Even if raw text reached the frontend, the `sanitizeCodeSnippet()` utility intercepts each line and executes string replacements before inserting text into the React DOM:
  ```typescript
  export function sanitizeCodeSnippet(line: string): string {
    return line
      .replace(/AKIA[0-9A-Z]{16}/g, "[REDACTED SECRET]")
      .replace(/ghp_[0-9a-zA-Z]{36}/g, "[REDACTED SECRET]")
      .replace(/gho_[0-9a-zA-Z]{36}/g, "[REDACTED SECRET]")
      .replace(/github_pat_[0-9a-zA-Z_]{22,}/g, "[REDACTED SECRET]")
      .replace(/sk_live_[0-9a-zA-Z_]{16,}/g, "[REDACTED SECRET]")
      .replace(/rk_live_[0-9a-zA-Z_]{16,}/g, "[REDACTED SECRET]")
      .replace(/eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}/g, "[REDACTED SECRET]")
      .replace(/-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----/g, "[REDACTED SECRET]")
      .replace(/(['"]?(?:password|passwd|secret|api_key|apikey|token|access_key|auth_token)['"]?\s*[:=]\s*['"])[^'"]{8,}(['"])/gi, "$1[REDACTED SECRET]$2");
  }
  ```

---

## 4. Remediation Guidance

When a secret exposure finding is flagged:
1. **Revoke Immediately**: The credential must be revoked in the upstream provider (e.g., AWS IAM, GitHub Developer Settings, Stripe Dashboard). Never rely solely on deleting the code commit.
2. **Rotate Credentials**: Issue a new credential and store it exclusively in secure secret stores (e.g., HashiCorp Vault, AWS Secrets Manager, GitHub Secrets).
3. **Rewrite Git History**: If the secret was committed to a branch, use tools like `git-filter-repo` or BFG Repo-Cleaner to purge the sensitive string from commit trees.
4. **Authoritative Re-Scan**: Run `POST /api/v1/ai/analyses/{id}/re-scan` or trigger the **Authoritative Re-Scan** action in ForgeAI to verify that the pattern matcher confirms zero remaining instances.
