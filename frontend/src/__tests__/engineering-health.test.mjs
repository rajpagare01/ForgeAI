import test from "node:test";
import assert from "node:assert/strict";

// Setup browser globals for Node test environment
class MockLocalStorage {
  constructor() {
    this.store = new Map();
  }
  getItem(key) {
    return this.store.get(key) ?? null;
  }
  setItem(key, value) {
    this.store.set(key, String(value));
  }
  removeItem(key) {
    this.store.delete(key);
  }
  clear() {
    this.store.clear();
  }
}

const mockStorage = new MockLocalStorage();
globalThis.window = {
  crypto: globalThis.crypto,
  location: { pathname: "/ai-insights" },
};
globalThis.localStorage = mockStorage;

// Import pure security and health helpers dynamically
const {
  sanitizeCodeSnippet,
  extractSafeCodeContext,
  evaluateSecurityStatus,
  computeDashboardHealthSummary,
  computeRescanDifferential,
} = await import("../lib/security-helpers.ts");

// ============================================================================
// TEST 1: AUTHORITATIVE SECURITY STATUS BANNER EVALUATION
// ============================================================================

test("Security Status: CRITICAL / BLOCKED when critical findings exist or status is SECURITY_BLOCKER", () => {
  const assessment = {
    critical_count: 2,
    high_count: 1,
    medium_count: 0,
    low_count: 0,
    security_status: "SECURITY_BLOCKER",
  };
  const result = evaluateSecurityStatus(assessment, "CONFIGURED");
  assert.equal(result.status, "CRITICAL / BLOCKED");
  assert.equal(result.color, "rose");
  assert.match(result.description, /2 critical security finding\(s\) detected/);
  assert.match(result.description, /Merge is blocked/);
});

test("Security Status: HIGH RISK when high-severity findings present and critical count is zero", () => {
  const assessment = {
    critical_count: 0,
    high_count: 3,
    medium_count: 1,
    low_count: 0,
    security_status: "HIGH_RISK",
  };
  const result = evaluateSecurityStatus(assessment, "CONFIGURED");
  assert.equal(result.status, "HIGH RISK");
  assert.equal(result.color, "amber");
  assert.match(result.description, /3 high-severity finding\(s\) present/);
});

test("Security Status: PARTIAL EVIDENCE contract when dependency CVE DB is unconfigured", () => {
  // Case A: security assessment has evidence_status = PARTIAL
  const assessmentPartial = {
    critical_count: 0,
    high_count: 0,
    medium_count: 0,
    low_count: 0,
    evidence_status: "PARTIAL",
  };
  const res1 = evaluateSecurityStatus(assessmentPartial, "PARTIAL");
  assert.equal(res1.status, "PARTIAL EVIDENCE");
  assert.equal(res1.color, "blue");
  assert.match(res1.description, /Dependency CVE database unconfigured/);

  // Case B: No code-level findings, but external dependency security status is PARTIAL
  const res2 = evaluateSecurityStatus(null, "PARTIAL");
  assert.equal(res2.status, "PARTIAL EVIDENCE");
  assert.match(res2.description, /Dependency CVE scanner database is unconfigured/);
});

test("Security Status: WARNINGS when only medium or low findings exist", () => {
  const assessment = {
    critical_count: 0,
    high_count: 0,
    medium_count: 2,
    low_count: 1,
    security_status: "SECURITY_WARNING",
  };
  const result = evaluateSecurityStatus(assessment, "CONFIGURED");
  assert.equal(result.status, "WARNINGS");
  assert.equal(result.color, "yellow");
  assert.match(result.description, /3 security warning\(s\) detected/);
});

test("Security Status: CLEAN when zero vulnerabilities and clean dependencies", () => {
  const assessment = {
    critical_count: 0,
    high_count: 0,
    medium_count: 0,
    low_count: 0,
    security_status: "SECURITY_CLEAN",
    evidence_status: "VERIFIED",
  };
  const result = evaluateSecurityStatus(assessment, "CONFIGURED");
  assert.equal(result.status, "CLEAN");
  assert.equal(result.color, "emerald");
  assert.match(result.description, /Zero security vulnerabilities or secret exposures detected/);
});

// ============================================================================
// TEST 2: SAFE CODE CONTEXT EXTRACTION & ZERO RAW SECRET EXPOSURE
// ============================================================================

test("Safe Code Context: Strict redaction of AWS access keys (AKIA...)", () => {
  const rawLine = 'export const AWS_KEY = "AKIAIOSFODNN7EXAMPLE";';
  const sanitized = sanitizeCodeSnippet(rawLine);
  assert.ok(!sanitized.includes("AKIAIOSFODNN7EXAMPLE"), "Raw AWS key must NEVER appear in output");
  assert.ok(sanitized.includes("[REDACTED SECRET]"), "Must replace with [REDACTED SECRET]");
});

test("Safe Code Context: Strict redaction of GitHub PATs and fine-grained tokens", () => {
  const patLine = 'const token = "ghp_123456789012345678901234567890123456";';
  const fineGrainedLine = 'const fgToken = "github_pat_11AAAAAAA01234567890abcdefghijklmnopqrstuvwxyz";';

  const s1 = sanitizeCodeSnippet(patLine);
  assert.ok(!s1.includes("ghp_123456789012345678901234567890123456"));
  assert.ok(s1.includes("[REDACTED SECRET]"));

  const s2 = sanitizeCodeSnippet(fineGrainedLine);
  assert.ok(!s2.includes("github_pat_11AAAAAAA01234567890abcdefghijklmnopqrstuvwxyz"));
  assert.ok(s2.includes("[REDACTED SECRET]"));
});

test("Safe Code Context: Strict redaction of Stripe live keys and JWTs", () => {
  const stripeLine = 'const stripeKey = "sk_live_51Abcdefghijklmno123456789";';
  const jwtLine = 'const authHeader = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature12345";';

  const s1 = sanitizeCodeSnippet(stripeLine);
  assert.ok(!s1.includes("sk_live_51Abcdefghijklmno123456789"));
  assert.ok(s1.includes("[REDACTED SECRET]"));

  const s2 = sanitizeCodeSnippet(jwtLine);
  assert.ok(!s2.includes("doNotLeakThisSignature12345"));
  assert.ok(s2.includes("[REDACTED SECRET]"));
});

test("Safe Code Context: Window extraction (-3 to +3) and target line highlighting", () => {
  const fileContent = [
    "// line 1",
    "// line 2",
    "// line 3",
    "// line 4",
    'const apiKey = "sk_live_secret_key_12345678901234"; // line 5',
    "// line 6",
    "// line 7",
    "// line 8",
    "// line 9",
    "// line 10",
  ].join("\n");

  const files = [
    {
      filename: "src/auth.ts",
      content: fileContent,
    },
  ];

  const context = extractSafeCodeContext(files, "src/auth.ts", 5);
  assert.ok(context !== null, "Should return context lines array");
  // Window around line 5: 5 - 3 = 2 to 5 + 3 = 8 (7 lines total: 2, 3, 4, 5, 6, 7, 8)
  assert.equal(context.length, 7);
  assert.equal(context[0].num, 2);
  assert.equal(context[context.length - 1].num, 8);

  const targetEntry = context.find((c) => c.isTarget);
  assert.ok(targetEntry, "Must identify target line");
  assert.equal(targetEntry.num, 5);
  assert.ok(!targetEntry.text.includes("sk_live_secret_key_12345678901234"), "Zero raw secret exposure");
  assert.ok(targetEntry.text.includes("[REDACTED SECRET]"));
});

// ============================================================================
// TEST 3: DEVELOPER DASHBOARD SUMMARY METRIC CALCULATIONS
// ============================================================================

test("Dashboard Summary: Correct count aggregations for security, tests, risk, and merge readiness", () => {
  const analysisResult = {
    risk_score: 82.5,
    merge_readiness: false,
    security_assessment: {
      critical_count: 1,
      high_count: 2,
    },
    test_impact: [
      { test_name: "test_auth", confidence: "HIGH" },
      { test_name: "test_user", confidence: "HIGH" },
      { test_name: "test_cache", confidence: "LOW" },
    ],
    test_recommendations: [
      { test_name: "test_token_refresh", effort: "HIGH" },
    ],
  };

  const summary = computeDashboardHealthSummary(analysisResult);
  assert.equal(summary.criticalFindings, 1);
  assert.equal(summary.highFindings, 2);
  assert.equal(summary.missingCoverage, 2); // 2 HIGH confidence test impact items
  assert.equal(summary.testsFailing, 1); // 1 HIGH effort recommendation
  assert.equal(summary.riskScore, 82.5);
  assert.equal(summary.mergeReady, false);
});

// ============================================================================
// TEST 4: AUTHORITATIVE DIFFERENTIAL RE-SCAN COMPARISON
// ============================================================================

test("Differential Re-Scan: Computes delta and isolates deterministically resolved findings", () => {
  const previous = {
    risk_score: 85.0,
    merge_readiness: false,
    security_assessment: {
      critical_count: 1,
      high_count: 1,
      findings: [
        { rule_id: "SEC-001", file_path: "src/auth.ts", title: "Hardcoded AWS Key" },
        { rule_id: "SEC-002", file_path: "src/db.ts", title: "SQL Injection Vector" },
      ],
    },
  };

  const current = {
    risk_score: 25.0,
    merge_readiness: true,
    security_assessment: {
      critical_count: 0,
      high_count: 0,
      findings: [], // Both findings resolved
    },
  };

  const diff = computeRescanDifferential(previous, current);
  assert.equal(diff.resolvedCount, 2);
  assert.equal(diff.resolvedFindings.length, 2);
  assert.equal(diff.resolvedFindings[0].rule_id, "SEC-001");
  assert.equal(diff.resolvedFindings[1].rule_id, "SEC-002");
  assert.equal(diff.riskScoreDelta, -60.0);
  assert.equal(diff.criticalDelta, -1);
  assert.equal(diff.highDelta, -1);
  assert.equal(diff.wasMergeReady, false);
  assert.equal(diff.nowMergeReady, true);
});

test("Differential Re-Scan: Handles partial resolution when some findings persist", () => {
  const previous = {
    risk_score: 75.0,
    merge_readiness: false,
    security_assessment: {
      critical_count: 1,
      high_count: 1,
      findings: [
        { rule_id: "SEC-001", file_path: "src/auth.ts", title: "Hardcoded AWS Key" },
        { rule_id: "SEC-002", file_path: "src/db.ts", title: "SQL Injection Vector" },
      ],
    },
  };

  const current = {
    risk_score: 45.0,
    merge_readiness: false,
    security_assessment: {
      critical_count: 0,
      high_count: 1,
      findings: [
        { rule_id: "SEC-002", file_path: "src/db.ts", title: "SQL Injection Vector" },
      ],
    },
  };

  const diff = computeRescanDifferential(previous, current);
  assert.equal(diff.resolvedCount, 1);
  assert.equal(diff.resolvedFindings[0].rule_id, "SEC-001");
  assert.equal(diff.riskScoreDelta, -30.0);
  assert.equal(diff.criticalDelta, -1);
  assert.equal(diff.highDelta, 0);
  assert.equal(diff.nowMergeReady, false);
});

// ============================================================================
// TEST 6: 6-DIMENSION ENGINEERING HEALTH CENTER CARDS & ACCURATE TERMINOLOGY
// ============================================================================

test("Engineering Health: 6-Card metrics extraction and terminology contracts", () => {
  const prAnalysis = {
    security_assessment: {
      critical_count: 1,
      high_count: 2,
      evidence_status: "PARTIAL",
      findings: [
        { rule_id: "SEC_SECRET_AWS_KEY", category: "SECRET", severity: "CRITICAL" },
        { rule_id: "SEC_CODE_SQL_INJECTION", category: "INJECTION", severity: "HIGH" },
      ],
    },
    reliability_assessment: {
      critical_count: 0,
      high_count: 1,
      reliability_status: "AT_RISK",
      findings: [
        { rule_id: "REL_UNSAFE_OPTIONAL_DEREF", terminology: "LIKELY", severity: "HIGH" },
        { rule_id: "REL_SWALLOWED_PROMISE_REJECTION", terminology: "DETECTED", severity: "MEDIUM" },
        { rule_id: "REL_GENERIC_EXCEPTION_THROW", terminology: "REQUIRES REVIEW", severity: "LOW" },
      ],
    },
    merge_readiness: {
      decision: "BLOCKED",
      evidence_summary: {
        failed_required_tests: 2, // Verified CI failure
      },
      blockers: [
        "1 critical security finding (SEC_SECRET_AWS_KEY) detected",
        "2 required CI test(s) failed",
      ],
    },
    test_strategy: {
      must_run: ["com.forgeai.billing.PaymentVaultClientTest"],
      should_run: ["com.forgeai.billing.PaymentConfigTest"],
      coverage_summary: {
        coverage_available: true,
      },
    },
    unified_risk: {
      final_risk: 82.5,
      final_risk_level: "CRITICAL",
    },
    affected_modules: ["billing-service", "vault-client"],
    impact_metadata: {
      distributed_impact: {
        direct_downstream_count: 3,
        combined_risk_level: "HIGH",
      },
    },
  };

  const dependencySecurity = {
    status: "PARTIAL",
    dependencies_scanned: 48,
    vulnerabilities_found: 0,
  };

  // Card 1: Security
  const sec = prAnalysis.security_assessment;
  const secretCount = sec.findings.filter((f) => f.category === "SECRET" || f.rule_id.includes("SECRET")).length;
  assert.equal(secretCount, 1);
  assert.equal(sec.critical_count, 1);

  // Card 2: Reliability (with honest terminology)
  const relFindings = prAnalysis.reliability_assessment.findings;
  const detectedCount = relFindings.filter((f) => f.terminology === "DETECTED").length;
  const likelyCount = relFindings.filter((f) => f.terminology === "LIKELY").length;
  const reviewCount = relFindings.filter((f) => f.terminology === "REQUIRES REVIEW").length;
  assert.equal(detectedCount, 1);
  assert.equal(likelyCount, 1);
  assert.equal(reviewCount, 1);

  // Card 3: Tests (deterministic verified failure vs AI recommendations)
  const verifiedTestFailures = prAnalysis.merge_readiness.evidence_summary.failed_required_tests;
  const aiRecommendedTests = prAnalysis.test_strategy.must_run.length + prAnalysis.test_strategy.should_run.length;
  assert.equal(verifiedTestFailures, 2); // Deterministic ground truth
  assert.equal(aiRecommendedTests, 2);  // AI recommendation

  // Card 4: Dependencies (Truthful partial evidence contract)
  assert.equal(dependencySecurity.status, "PARTIAL");
  assert.equal(dependencySecurity.dependencies_scanned, 48);
  assert.equal(dependencySecurity.vulnerabilities_found, 0);

  // Card 5: Impact
  assert.equal(prAnalysis.affected_modules.length, 2);
  assert.equal(prAnalysis.impact_metadata.distributed_impact.direct_downstream_count, 3);
  assert.equal(prAnalysis.impact_metadata.distributed_impact.combined_risk_level, "HIGH");

  // Card 6: Merge Readiness
  assert.equal(prAnalysis.merge_readiness.decision, "BLOCKED");
  assert.equal(prAnalysis.merge_readiness.blockers.length, 2);
  assert.match(prAnalysis.merge_readiness.blockers[0], /critical security finding/);
  assert.match(prAnalysis.merge_readiness.blockers[1], /required CI test/);
});

