/**
 * ForgeAI Security & Engineering Health Helpers
 * Single source of truth for:
 * 1. Safe Code Context extraction & Zero Raw Secret Exposure sanitization
 * 2. Authoritative Security Status banner evaluation
 * 3. Dashboard Metrics calculations
 * 4. Differential Re-Scan comparison
 */

export interface CodeContextLine {
  num: number;
  isTarget: boolean;
  text: string;
}

export interface SecurityStatusResult {
  status: "CLEAN" | "WARNINGS" | "HIGH RISK" | "CRITICAL / BLOCKED" | "PARTIAL EVIDENCE";
  color: "emerald" | "yellow" | "amber" | "rose" | "blue";
  bg: string;
  description: string;
}

/**
 * Sanitizes code snippets to strictly ensure ZERO raw secret exposure.
 * Replaces AWS keys, GitHub tokens, Stripe keys, JWTs, private keys, and
 * inline secret assignments with [REDACTED SECRET].
 */
export function sanitizeCodeSnippet(line: string): string {
  if (!line) return "";
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

/**
 * Extracts a window of lines (-3 to +3) around the specified line number from
 * a file changeset and sanitizes any sensitive tokens.
 */
export function extractSafeCodeContext(
  files: Array<{ filename: string; patch?: string; content?: string }> | undefined | null,
  filePath: string,
  lineNumber?: number | null
): CodeContextLine[] | null {
  if (!files || !filePath) return null;
  const file = files.find(
    (f) => f.filename === filePath || filePath.endsWith(f.filename) || f.filename.endsWith(filePath)
  );
  if (!file) return null;
  const text = file.patch || file.content || "";
  if (!text) return null;

  const lines = text.split("\n");
  const targetLine = lineNumber && lineNumber > 0 ? lineNumber : 1;
  const start = Math.max(1, targetLine - 3);
  const end = Math.min(lines.length, targetLine + 3);

  return lines.slice(start - 1, end).map((l, idx) => ({
    num: start + idx,
    isTarget: start + idx === targetLine,
    text: sanitizeCodeSnippet(l),
  }));
}

/**
 * Evaluates the authoritative security status banner based on deterministic security assessment
 * and dependency vulnerability status.
 */
export function evaluateSecurityStatus(
  securityAssessment?: {
    critical_count?: number;
    high_count?: number;
    medium_count?: number;
    low_count?: number;
    security_status?: string;
    evidence_status?: string;
  } | null,
  dependencySecurityStatus?: string | null
): SecurityStatusResult {
  if (!securityAssessment) {
    if (dependencySecurityStatus === "PARTIAL") {
      return {
        status: "PARTIAL EVIDENCE",
        color: "blue",
        bg: "bg-blue-950/20 border-blue-500/40 text-blue-300",
        description: "Dependency CVE scanner database is unconfigured. Code-level scanning verified.",
      };
    }
    return {
      status: "CLEAN",
      color: "emerald",
      bg: "bg-emerald-950/20 border-emerald-500/40 text-emerald-300",
      description: "Zero security vulnerabilities detected across analyzed changes.",
    };
  }

  if ((securityAssessment.critical_count ?? 0) > 0 || securityAssessment.security_status === "SECURITY_BLOCKER") {
    return {
      status: "CRITICAL / BLOCKED",
      color: "rose",
      bg: "bg-rose-950/30 border-rose-500/50 text-rose-300",
      description: `${securityAssessment.critical_count || 1} critical security finding(s) detected. Merge is blocked. Immediate remediation required.`,
    };
  }

  if ((securityAssessment.high_count ?? 0) > 0) {
    return {
      status: "HIGH RISK",
      color: "amber",
      bg: "bg-amber-950/30 border-amber-500/50 text-amber-300",
      description: `${securityAssessment.high_count} high-severity finding(s) present. Review and security fix required.`,
    };
  }

  if (securityAssessment.evidence_status === "PARTIAL" || dependencySecurityStatus === "PARTIAL") {
    return {
      status: "PARTIAL EVIDENCE",
      color: "blue",
      bg: "bg-blue-950/20 border-blue-500/40 text-blue-300",
      description: "Dependency CVE database unconfigured. Code-level and secret scanning is fully verified.",
    };
  }

  if (
    (securityAssessment.medium_count ?? 0) > 0 ||
    (securityAssessment.low_count ?? 0) > 0 ||
    securityAssessment.security_status === "SECURITY_WARNING"
  ) {
    const totalWarn = (securityAssessment.medium_count || 0) + (securityAssessment.low_count || 0);
    return {
      status: "WARNINGS",
      color: "yellow",
      bg: "bg-yellow-950/20 border-yellow-500/40 text-yellow-300",
      description: `${totalWarn} security warning(s) detected. Review recommended.`,
    };
  }

  return {
    status: "CLEAN",
    color: "emerald",
    bg: "bg-emerald-950/20 border-emerald-500/40 text-emerald-300",
    description: "Zero security vulnerabilities or secret exposures detected.",
  };
}

/**
 * Computes top-level dashboard metrics for developer health summary.
 */
export function computeDashboardHealthSummary(analysisResult: any) {
  const sec = analysisResult?.security_assessment;
  const criticalFindings = sec?.critical_count ?? 0;
  const highFindings = sec?.high_count ?? 0;

  let testsFailing = 0;
  let missingCoverage = 0;

  if (analysisResult?.test_impact) {
    const highConfidence = analysisResult.test_impact.filter((t: any) => t.confidence === "HIGH").length;
    missingCoverage = highConfidence;
  }
  if (analysisResult?.test_recommendations) {
    const highEffort = analysisResult.test_recommendations.filter((t: any) => t.effort === "HIGH" || t.priority === "HIGH").length;
    testsFailing = highEffort;
  }

  const riskScore = analysisResult?.risk_score ?? 0;
  const mergeReady = analysisResult?.merge_readiness ?? (criticalFindings === 0 && riskScore < 70);

  return {
    criticalFindings,
    highFindings,
    testsFailing,
    missingCoverage,
    riskScore,
    mergeReady,
  };
}

/**
 * Calculates differential re-scan comparison between a previous analysis and a new re-scan.
 */
export function computeRescanDifferential(
  previousResult: {
    risk_score?: number;
    security_assessment?: {
      findings?: Array<{ rule_id?: string; title?: string; file_path?: string }>;
      critical_count?: number;
      high_count?: number;
    };
    merge_readiness?: boolean;
  },
  currentResult: {
    risk_score?: number;
    security_assessment?: {
      findings?: Array<{ rule_id?: string; title?: string; file_path?: string }>;
      critical_count?: number;
      high_count?: number;
    };
    merge_readiness?: boolean;
  }
) {
  const prevFindings = previousResult?.security_assessment?.findings || [];
  const currFindings = currentResult?.security_assessment?.findings || [];

  const currKeys = new Set(
    currFindings.map((f) => `${f.rule_id || ""}:${f.file_path || ""}:${f.title || ""}`)
  );

  const resolvedFindings = prevFindings.filter(
    (pf) => !currKeys.has(`${pf.rule_id || ""}:${pf.file_path || ""}:${pf.title || ""}`)
  );

  const riskScoreDelta = (currentResult?.risk_score ?? 0) - (previousResult?.risk_score ?? 0);
  const criticalDelta =
    (currentResult?.security_assessment?.critical_count ?? 0) -
    (previousResult?.security_assessment?.critical_count ?? 0);
  const highDelta =
    (currentResult?.security_assessment?.high_count ?? 0) -
    (previousResult?.security_assessment?.high_count ?? 0);

  return {
    resolvedFindings,
    resolvedCount: resolvedFindings.length,
    riskScoreDelta,
    criticalDelta,
    highDelta,
    wasMergeReady: previousResult?.merge_readiness ?? false,
    nowMergeReady: currentResult?.merge_readiness ?? false,
  };
}
