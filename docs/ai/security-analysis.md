# AI Intelligence: Security Analysis & Grounded Explanation Layer

## 1. Overview & Philosophy

In ForgeAI, AI and Large Language Models (LLMs) operate strictly as **reasoning, contextualization, and remediation assistants**. They do not act as authoritative detection mechanisms. 

### Core Tenet: Grounded Factuality
LLMs are inherently probabilistic and prone to hallucinations when asked to enumerate vulnerabilities from scratch. Conversely, static pattern matchers, AST parsers, and dependency manifest resolvers provide deterministic, reproducible detection.

By separating **Deterministic Detection** from **AI Reasoning**, ForgeAI achieves:
1. **Zero Hallucinated Findings**: Every finding originated from a verified rule match.
2. **Authoritative Verification**: Only deterministic re-scans can mark a finding as fixed.
3. **Rich Developer Context**: LLMs explain complex vulnerabilities, impact radius, and secure fixes based strictly on confirmed findings.

---

## 2. Grounded Explanation Contract (`POST /api/v1/ai/explain-finding`)

When a developer clicks **"Explain with ForgeAI"** on a finding, the frontend issues a request to `/api/v1/ai/explain-finding` with the exact finding metadata:

```json
{
  "finding_id": "FINDING-SEC-001",
  "rule_id": "SEC-SECRET-001",
  "category": "HARDCODED_SECRET",
  "severity": "CRITICAL",
  "title": "Hardcoded AWS Access Key ID Detected",
  "file_path": "src/config/aws.ts",
  "line_number": 14,
  "code_snippet": "export const AWS_ACCESS_KEY_ID = \"AKIA***[REDACTED_LEN_20]\";",
  "risk_impact": "Exposing root or IAM AWS access keys enables unauthorized infrastructure access.",
  "remediation_guidance": "Revoke the key in AWS IAM and inject credentials via AWS_ACCESS_KEY_ID environment variables."
}
```

### Prompt Grounding Prompt Template

The prompt sent to the LLM enforces strict guardrails:

```text
You are ForgeAI's authoritative Security and Engineering Health Analyst.
Analyze the following GROUNDED finding metadata identified by deterministic rule engines:

Rule ID: {rule_id}
Category: {category}
Severity: {severity}
Title: {title}
File: {file_path}:{line_number}
Evidence Snippet: {code_snippet}
Risk Impact: {risk_impact}

STRICT GUARDRAILS:
1. Do NOT fabricate, invent, or assume any CVE numbers not explicitly listed.
2. Rely ONLY on the verified evidence provided.
3. Provide:
   - Technical breakdown of how this vulnerability/secret can be exploited.
   - Blast radius analysis for the affected component.
   - Precise, secure code remediation steps.
```

### Structured Explanation Response

The AI Service returns a structured response matching `ExplainFindingResponseDTO`:
- `explanation`: Thorough technical breakdown of the mechanism and risk.
- `attack_vector`: Theoretical exploit vector or attack scenario.
- `secure_fix_example`: Code snippet exemplifying the secure pattern.
- `suggested_next_steps`: List of immediate actionable engineering tasks (e.g., rotate credentials, update CI variables).

---

## 3. Authoritative Differential Re-Scan Contract

When an engineer pushes fixes or requests a re-scan via `/api/v1/ai/analyses/{id}/re-scan`:

1. **Deterministic Execution**:
   - The AI Service executes `SecurityIntelligenceEngine.analyze_repository_snapshot()` or pull request changes on the updated codebase.
   - The scan evaluates rules, secrets, and dependency manifests.
2. **Delta Calculation**:
   - The engine computes the difference between previous findings and current findings.
   - A finding is marked **Resolved** if and only if its `(rule_id, file_path, line_number)` no longer matches any deterministic rule in the updated files.
3. **Merge Readiness Determination**:
   - If critical findings reach 0 and risk score falls below the threshold, merge readiness flips to `MERGE_READY`.
   - LLMs cannot override or declare an issue resolved without this mathematical verification.
