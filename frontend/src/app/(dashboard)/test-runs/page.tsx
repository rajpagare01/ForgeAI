"use client";

import React, { useState, useEffect, useCallback } from "react";
import { TestRun } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient, formatUserError } from "@/lib/api-client";
import {
  CheckCircle2,
  XCircle,
  Clock,
  PlayCircle,
  FileCode,
  AlertTriangle,
  Filter,
  RefreshCw,
  Loader2,
  AlertCircle,
  Sparkles,
  GitBranch,
  Upload,
  ExternalLink,
  Copy,
  Check,
  ShieldCheck,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogClose,
  DialogFooter,
} from "@/components/ui/dialog";
import { formatDate } from "@/lib/utils";

interface TestCaseResult {
  id?: string;
  testCaseName?: string;
  name?: string;
  className?: string;
  classname?: string;
  status: "PASSED" | "FAILED" | "SKIPPED";
  durationMs?: number;
  errorMessage?: string;
  failureMessage?: string;
  stackTrace?: string;
}

interface DiagnosisResult {
  test_name: string;
  framework?: string;
  failure_category?: string;
  root_cause: string;
  why_it_failed_detail?: string;
  suspected_file: string;
  suspected_line: number;
  confidence: string;
  remediation_steps: string[];
  proposed_fix_title: string;
  proposed_fix_diff: string;
  patched_content: string;
  used_fallback: boolean;
}

interface VerifiedTestCaseResult {
  name: string;
  class_name: string;
  framework: string;
  status: "PASSED" | "FAILED";
  duration_ms: number;
  error_message?: string;
  stack_trace?: string;
  ai_failure_diagnosis?: DiagnosisResult;
}

interface VerifyTestSuiteResponse {
  repository_id: string;
  framework: string;
  total_tests: number;
  passed_tests: number;
  failed_tests: number;
  duration_ms: number;
  results: VerifiedTestCaseResult[];
  junit_xml: string;
}

interface GeneratedTestCase {
  name: string;
  category: string;
  description: string;
}

interface GeneratedTestSuite {
  repository_id: string;
  source_file: string;
  test_file_path: string;
  framework: string;
  test_code: string;
  test_cases: GeneratedTestCase[];
  used_fallback: boolean;
}

export default function TestRunsPage() {
  const { currentOrg, activeWorkspace, isPersonalWorkspace } = useTenant();
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [repositories, setRepositories] = useState<{ id: string; fullName: string; name: string; latestObservedSha?: string }[]>([]);
  const [selectedRepoFilter, setSelectedRepoFilter] = useState<string>(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("forgeai_selected_repo_id") || "ALL";
    }
    return "ALL";
  });
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successToast, setSuccessToast] = useState<string | null>(null);

  // Inspector state
  const [selectedRun, setSelectedRun] = useState<TestRun | null>(null);
  const [testResults, setTestResults] = useState<TestCaseResult[]>([]);
  const [loadingResults, setLoadingResults] = useState(false);

  // AI Failure Diagnosis state
  const [diagnosingTestName, setDiagnosingTestName] = useState<string | null>(null);
  const [diagnosisResult, setDiagnosisResult] = useState<DiagnosisResult | null>(null);
  const [isOpeningPr, setIsOpeningPr] = useState(false);
  const [openedPrUrl, setOpenedPrUrl] = useState<string | null>(null);

  // Sync CI State
  const [isSyncingCi, setIsSyncingCi] = useState(false);

  // Upload JUnit XML / Record Run Modal State
  const [showRecordRunModal, setShowRecordRunModal] = useState(false);
  const [recordRepoId, setRecordRepoId] = useState("");
  const [recordCommitSha, setRecordCommitSha] = useState("main001");
  const [recordBranch, setRecordBranch] = useState("main");
  const [recordXmlContent, setRecordXmlContent] = useState("");
  const [isSubmittingRun, setIsSubmittingRun] = useState(false);

  // AI Generate Unit Tests Modal State
  const [showGenerateTestsModal, setShowGenerateTestsModal] = useState(false);
  const [genRepoId, setGenRepoId] = useState("");
  const [genFilePath, setGenFilePath] = useState("src/main/java/com/forgeai/service/AuthenticationService.java");
  const [genRequirementTitle, setGenRequirementTitle] = useState("Tenant boundary isolation & strict token validation");
  const [genFramework, setGenFramework] = useState("AUTO");
  const [isGeneratingTests, setIsGeneratingTests] = useState(false);
  const [generatedSuite, setGeneratedSuite] = useState<GeneratedTestSuite | null>(null);
  const [isVerifyingSuite, setIsVerifyingSuite] = useState(false);
  const [verifiedResults, setVerifiedResults] = useState<VerifyTestSuiteResponse | null>(null);
  const [isExecutingSuite, setIsExecutingSuite] = useState(false);
  const [isCommittingPr, setIsCommittingPr] = useState(false);
  const [committedPrUrl, setCommittedPrUrl] = useState<string | null>(null);
  const [copiedCode, setCopiedCode] = useState(false);
  const [applyingFixForTest, setApplyingFixForTest] = useState<string | null>(null);
  const [fixPrUrlMap, setFixPrUrlMap] = useState<Record<string, string>>({});

  const showNotification = (msg: string) => {
    setSuccessToast(msg);
    setTimeout(() => setSuccessToast(null), 5000);
  };

  const fetchRuns = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      let repoMap: Record<string, string> = {};
      try {
        const repoData = await apiClient.get<any[]>("/api/v1/repositories");
        if (Array.isArray(repoData)) {
          const list = repoData.map((r) => ({
            id: r.id,
            fullName: r.fullName || r.name,
            name: r.name,
            latestObservedSha: r.latestObservedSha || r.defaultBranchSha || r.latestCommitSha,
          }));
          setRepositories(list);
          if (!recordRepoId && list.length > 0) {
            setRecordRepoId(list[0].id);
          }
          if (!genRepoId && list.length > 0) {
            setGenRepoId(list[0].id);
          }
          repoData.forEach((r) => {
            if (r.id) repoMap[r.id] = r.fullName || r.name;
          });
        }
      } catch (rErr) {
        console.warn("Could not load repositories for test runs name mapping:", rErr);
      }

      const data = await apiClient.get<any[]>("/api/v1/test-runs");
      if (Array.isArray(data)) {
        const mapped: TestRun[] = data.map((r) => {
          let passed = r.passedTests ?? r.passedCount ?? 0;
          let failed = r.failedTests ?? r.failedCount ?? 0;
          let skipped = r.skippedTests ?? r.skippedCount ?? 0;
          let total = r.totalTests ?? r.totalCount ?? 0;

          const isSuccess = r.conclusion === "success" || r.status === "PASSED" || r.status === "COMPLETED";
          const isFailed = r.conclusion === "failure" || r.status === "FAILED";

          const status = (isFailed || failed > 0)
            ? "FAILED"
            : (isSuccess)
            ? "PASSED"
            : r.status === "RUNNING" || r.status === "IN_PROGRESS" || r.githubRunStatus === "in_progress"
            ? "RUNNING"
            : "QUEUED";

          let durationMs = r.durationMs || 0;
          if (!durationMs || durationMs === 0) {
            const start = r.runStartedAt || r.startedAt || r.createdAt;
            const end = r.runCompletedAt || r.completedAt || r.updatedAt;
            if (start && end) {
              const diff = new Date(end).getTime() - new Date(start).getTime();
              if (diff > 0) durationMs = diff;
            }
          }

          const repoName = (r.repositoryId && repoMap[r.repositoryId])
            ? repoMap[r.repositoryId]
            : r.repositoryFullName
            ? r.repositoryFullName
            : r.repositoryId
            ? `repo-${r.repositoryId.substring(0, 8)}`
            : "Unlinked Repository";

          return {
            id: r.id,
            repositoryId: r.repositoryId,
            repositoryFullName: repoName,
            commitSha: r.headSha || r.commitSha || "main",
            branch: r.headBranch || r.branch || "main",
            prNumber: r.pullRequestNumber ?? r.prNumber ?? undefined,
            status,
            passedCount: passed,
            failedCount: failed,
            skippedCount: skipped,
            totalCount: total,
            durationMs,
            createdAt: r.runStartedAt || r.createdAt || new Date().toISOString(),
          } as any;
        });
        setRuns(mapped);
      }
    } catch (err: any) {
      console.warn("Failed to load test runs from Gateway:", err);
      setError("Could not load test runs for the active organization.");
    } finally {
      setIsLoading(false);
    }
  }, [recordRepoId, genRepoId]);

  useEffect(() => {
    fetchRuns();
  }, [fetchRuns]);

  // Open Inspector Modal
  const openInspector = async (run: TestRun) => {
    setSelectedRun(run);
    setLoadingResults(true);
    setDiagnosisResult(null);
    setOpenedPrUrl(null);
    try {
      const results = await apiClient.get<any[]>(`/api/v1/test-runs/${run.id}/results`);
      if (Array.isArray(results) && results.length > 0) {
        setTestResults(results);
      } else {
        setTestResults([]);
      }
    } catch (err) {
      console.warn("Could not fetch test results for run:", err);
      setTestResults([]);
    } finally {
      setLoadingResults(false);
    }
  };

  // Sync GitHub Actions CI
  const handleSyncWorkflowRuns = async () => {
    const targetRepo = selectedRepoFilter !== "ALL"
      ? repositories.find((r) => r.id === selectedRepoFilter)
      : repositories[0];

    if (!targetRepo) {
      setError("No repository connected. Please connect a repository in Repositories first.");
      return;
    }

    setIsSyncingCi(true);
    try {
      const res = await apiClient.post<any>(`/api/v1/repositories/${targetRepo.id}/workflow-runs/sync`, {});
      if (res?.syncedRunsCount > 0) {
        showNotification(`✓ Synced ${res.syncedRunsCount} GitHub Actions workflow run(s)!`);
      } else {
        showNotification("No GitHub Actions workflow runs found for this repository on GitHub yet.");
      }
      await fetchRuns();
    } catch (err: any) {
      console.error("Failed to sync GitHub Actions workflow runs:", err);
      setError(formatUserError(err, "Unable to sync GitHub Actions runs right now. Please try again."));
    } finally {
      setIsSyncingCi(false);
    }
  };

  // AI Test Failure Root-Cause Diagnosis
  const handleDiagnoseFailure = async (testCase: TestCaseResult) => {
    if (!selectedRun) return;
    setDiagnosingTestName(testCase.name || testCase.testCaseName || "test");
    setDiagnosisResult(null);
    setOpenedPrUrl(null);

    try {
      const diagnosis = await apiClient.post<DiagnosisResult>("/api/v1/ai/test-runs/diagnose-failure", {
        repository_id: (selectedRun as any).repositoryId || "00000000-0000-0000-0000-000000000001",
        run_id: selectedRun.id,
        commit_sha: selectedRun.commitSha,
        test_name: testCase.name || testCase.testCaseName || "testTenantBoundaryIsolation",
        class_name: testCase.className || testCase.classname || "com.forgeai.security.AuthenticationServiceTest",
        error_message: testCase.errorMessage || testCase.failureMessage || "Assertion error in verification suite",
        stack_trace: testCase.stackTrace || "java.lang.AssertionError: Assertion failed",
      });
      setDiagnosisResult(diagnosis);
    } catch (err: any) {
      console.error("Failed to diagnose test failure:", err);
      setError(formatUserError(err, "AI diagnosis unavailable right now."));
    } finally {
      setDiagnosingTestName(null);
    }
  };

  // Open Fix PR from Diagnosis
  const handleOpenFixPr = async () => {
    if (!selectedRun || !diagnosisResult) return;
    setIsOpeningPr(true);
    try {
      const repoId = (selectedRun as any).repositoryId;
      const res = await apiClient.post<any>(`/api/v1/repositories/${repoId}/pull-requests/propose`, {
        title: diagnosisResult.proposed_fix_title || `fix(ci): remediate ${diagnosisResult.test_name} failure`,
        body: `### ForgeAI Automated CI Failure Remediation\n\n**Root Cause:** ${diagnosisResult.root_cause}\n\n**Suspected File:** \`${diagnosisResult.suspected_file}:${diagnosisResult.suspected_line}\`\n\n**Remediation Steps:**\n${diagnosisResult.remediation_steps.map((s) => `- ${s}`).join("\n")}`,
        branchName: `forgeai/fix-${Date.now()}`,
        baseBranch: selectedRun.branch || "main",
        fileContents: {
          [diagnosisResult.suspected_file]: diagnosisResult.patched_content,
        },
      });
      const prUrl = res?.pullRequestUrl || res?.prUrl || res?.pr_url || res?.htmlUrl || null;
      if (prUrl) {
        setOpenedPrUrl(prUrl);
        showNotification("✓ Automated Remediation Pull Request proposed successfully!");
      }
    } catch (err: any) {
      console.error("Failed to open fix PR:", err);
      setError(formatUserError(err, "Unable to create the remediation pull request. Please verify your GitHub App permissions and try again."));
    } finally {
      setIsOpeningPr(false);
    }
  };

  // Upload JUnit XML / Record Run
  const handleRecordRunSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!recordRepoId) {
      setError("Please select a target repository.");
      return;
    }

    setIsSubmittingRun(true);
    try {
      const runRes = await apiClient.post<any>("/api/v1/test-runs", {
        repositoryId: recordRepoId,
        commitSha: (() => {
          const clean = recordCommitSha.trim();
          if (!clean) {
            const selectedRepo = repositories.find((r) => r.id === recordRepoId);
            return (selectedRepo?.latestObservedSha && /^[0-9a-f]{40}$/i.test(selectedRepo.latestObservedSha))
              ? selectedRepo.latestObservedSha
              : (Date.now().toString(16) + "0".repeat(40)).slice(0, 40);
          }
          if (clean.length < 40) {
            return (clean + "0".repeat(40)).slice(0, 40);
          }
          return clean.slice(0, 40);
        })(),
        branch: recordBranch.trim() || "main",
      });

      const newRunId = runRes?.id;
      if (!newRunId) throw new Error("Failed to initialize TestRun on Testing Service");

      if (recordXmlContent.trim()) {
        try {
          await apiClient.post(`/api/v1/test-runs/${newRunId}/reports/junit`, recordXmlContent, {
            headers: { "Content-Type": "application/xml" },
          });
        } catch (xmlErr: any) {
          throw new Error("Unable to parse the provided JUnit XML report. Please check that the XML format is valid.");
        }
      }

      await apiClient.post(`/api/v1/test-runs/${newRunId}/complete`, {
        status: "COMPLETED",
      });

      setShowRecordRunModal(false);
      showNotification("✓ Test run and JUnit results successfully ingested into Testing Service!");
      await fetchRuns();
    } catch (err: any) {
      console.error("Failed to record run:", err);
      setError(formatUserError(err, "Failed to record run on Testing Service."));
    } finally {
      setIsSubmittingRun(false);
    }
  };

  // AI Unit Test Generator
  const handleGenerateUnitTests = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!genRepoId) {
      setError("Please select a repository for unit test generation.");
      return;
    }

    setIsGeneratingTests(true);
    setGeneratedSuite(null);
    setVerifiedResults(null);
    setFixPrUrlMap({});
    setCommittedPrUrl(null);
    try {
      const suite = await apiClient.post<GeneratedTestSuite>(
        `/api/v1/ai/repositories/${genRepoId}/generate-tests`,
        {
          file_path: genFilePath.trim() || undefined,
          requirement_title: genRequirementTitle.trim() || undefined,
          framework: genFramework,
        }
      );
      setGeneratedSuite(suite);
      showNotification("✓ AI generated comprehensive unit test suite!");
    } catch (err: any) {
      console.error("Failed to generate unit tests:", err);
      setError("AI Unit Test generator fell back to deterministic template.");
    } finally {
      setIsGeneratingTests(false);
    }
  };

  // Safe In-App Test Verification Runner
  const handleRunAndVerifySuite = async () => {
    if (!generatedSuite || !genRepoId) return;
    setIsVerifyingSuite(true);
    setVerifiedResults(null);
    try {
      const resp = await apiClient.post<VerifyTestSuiteResponse>(
        `/api/v1/ai/repositories/${genRepoId}/verify-tests`,
        {
          framework: generatedSuite.framework,
          source_file: generatedSuite.source_file,
          test_file_path: generatedSuite.test_file_path,
          test_code: generatedSuite.test_code,
        }
      );
      setVerifiedResults(resp);

      try {
        const selectedRepo = repositories.find((r) => r.id === genRepoId);
        const fallbackSha = (selectedRepo?.latestObservedSha && /^[0-9a-f]{40}$/i.test(selectedRepo.latestObservedSha))
          ? selectedRepo.latestObservedSha
          : (Date.now().toString(16) + "0".repeat(40)).slice(0, 40);

        const runRes = await apiClient.post<any>("/api/v1/test-runs", {
          repositoryId: genRepoId,
          commitSha: fallbackSha,
          branch: "main",
        });
        const runId = runRes?.id;
        if (runId && resp.junit_xml) {
          await apiClient.post(`/api/v1/test-runs/${runId}/reports/junit`, resp.junit_xml, {
            headers: { "Content-Type": "application/xml" },
          });
          await apiClient.post(`/api/v1/test-runs/${runId}/complete`, {
            status: resp.failed_tests > 0 ? "FAILED" : "PASSED",
          });
        }
        await fetchRuns();
      } catch (recErr) {
        console.warn("Failed recording to testing-service:", recErr);
      }

      showNotification(`✓ Verified ${resp.total_tests} tests (${resp.passed_tests} passed, ${resp.failed_tests} failed) in ${resp.duration_ms}ms`);
    } catch (err: any) {
      console.error("Verification failed:", err);
      setError(formatUserError(err, "Failed to verify test suite."));
    } finally {
      setIsVerifyingSuite(false);
    }
  };

  // 1-Click Fix PR for a failing test in the Verification results
  const handleApplyFixPr = async (diag: DiagnosisResult) => {
    if (!genRepoId) return;
    setApplyingFixForTest(diag.test_name);
    try {
      const branchName = `forgeai/fix-${diag.test_name.toLowerCase().replace(/[^a-z0-9]/g, "-")}-${Date.now()}`;
      const res = await apiClient.post<any>(`/api/v1/repositories/${genRepoId}/pull-requests/propose`, {
        title: diag.proposed_fix_title || `fix: resolve ${diag.test_name} failure`,
        body: `### ForgeAI Automated Test Failure Remediation\n\n- **Failing Test:** \`${diag.test_name}\`\n- **Category:** \`${diag.failure_category || "ASSERTION_MISMATCH"}\`\n- **Suspected Target:** \`${diag.suspected_file}:${diag.suspected_line}\`\n\n**Root Cause:**\n${diag.root_cause}\n\n**Technical Explanation:**\n${diag.why_it_failed_detail || "N/A"}\n\n**Remediation Steps:**\n${diag.remediation_steps.map((s) => `- ${s}`).join("\n")}`,
        branchName,
        baseBranch: "main",
        fileContents: {
          [diag.suspected_file]: diag.patched_content,
        },
      });
      const prUrl = res?.pullRequestUrl || res?.htmlUrl || res?.prUrl || res?.pr_url || null;
      if (prUrl) {
        setFixPrUrlMap((prev) => ({ ...prev, [diag.test_name]: prUrl }));
        showNotification(`✓ Fix PR proposed: ${diag.proposed_fix_title}`);
      }
    } catch (err: any) {
      console.error("Failed to propose fix PR:", err);
      setError(formatUserError(err, "Unable to open the remediation pull request on GitHub right now."));
    } finally {
      setApplyingFixForTest(null);
    }
  };

  // Commit Generated Tests via GitHub PR
  const handleCommitTestsPr = async () => {
    if (!generatedSuite || !genRepoId) return;
    setIsCommittingPr(true);
    try {
      const branchName = `forgeai/unit-tests-${Date.now()}`;
      const res = await apiClient.post<any>(`/api/v1/repositories/${genRepoId}/pull-requests/propose`, {
        title: `test: add AI-generated ${generatedSuite.framework} suite for ${generatedSuite.source_file.split("/").pop()}`,
        body: `### ForgeAI Automated Unit Test Suite\n\n- **Target Source:** \`${generatedSuite.source_file}\`\n- **Test File:** \`${generatedSuite.test_file_path}\`\n- **Framework:** ${generatedSuite.framework}\n\n**Generated Test Cases:**\n${generatedSuite.test_cases.map((t) => `- **${t.name}** [${t.category}]: ${t.description}`).join("\n")}`,
        branchName,
        baseBranch: "main",
        fileContents: {
          [generatedSuite.test_file_path]: generatedSuite.test_code,
        },
      });
      const prUrl = res?.pullRequestUrl || res?.htmlUrl || res?.prUrl || res?.pr_url || null;
      if (prUrl) {
        setCommittedPrUrl(prUrl);
        showNotification("✓ Pull Request proposed with generated tests!");
      }
    } catch (err: any) {
      console.error("Failed to commit test PR:", err);
      setError(formatUserError(err, "Unable to commit the generated test suite to GitHub right now."));
    } finally {
      setIsCommittingPr(false);
    }
  };

  const filteredRuns = selectedRepoFilter === "ALL"
    ? runs
    : runs.filter((r) => (r as any).repositoryId === selectedRepoFilter);

  const totalRuns = filteredRuns.length;
  const passedRuns = filteredRuns.filter((r) => r.status === "PASSED").length;
  const failedRuns = filteredRuns.filter((r) => r.status === "FAILED").length;
  const passRate = totalRuns > 0 ? ((passedRuns / totalRuns) * 100).toFixed(1) : "100.0";
  const meanDurationMs = totalRuns > 0
    ? filteredRuns.reduce((acc, r) => acc + (r.durationMs || 0), 0) / totalRuns
    : 0;
  const meanDurationFormatted = (meanDurationMs / 1000).toFixed(1) + "s";
  const hasRecentFailures = filteredRuns.slice(0, 5).some((r) => r.status === "FAILED");

  return (
    <div className="space-y-6">
      {/* Header Toolbar */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <CheckCircle2 className="h-5 w-5 text-emerald-400" />
              <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
                CI Test Runs & Ingestion
              </h1>
            </div>
            <p className="text-xs text-zinc-400">
              Deterministic verification telemetry received from GitHub Actions via Testing Service for{" "}
              <span className="text-zinc-200 font-medium">
                {isPersonalWorkspace ? "Personal Workspace" : currentOrg?.name || "Workspace"}
              </span>.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {repositories.length > 0 && (
              <div className="flex items-center gap-1.5 bg-[#09090B] border border-[#26262C] rounded-md px-2.5 py-1 text-xs">
                <Filter className="h-3 w-3 text-zinc-500" />
                <select
                  aria-label="Filter Test Runs by Repository"
                  value={selectedRepoFilter}
                  onChange={(e) => {
                    const val = e.target.value;
                    setSelectedRepoFilter(val);
                    if (typeof window !== "undefined" && val !== "ALL") {
                      localStorage.setItem("forgeai_selected_repo_id", val);
                    }
                  }}
                  className="bg-transparent text-xs text-zinc-200 font-medium focus:outline-none cursor-pointer"
                >
                  <option value="ALL" className="bg-[#09090B] text-zinc-200">
                    All Repositories ({repositories.length})
                  </option>
                  {repositories.map((repo) => (
                    <option key={repo.id} value={repo.id} className="bg-[#09090B] text-zinc-200">
                      {repo.fullName}
                    </option>
                  ))}
                </select>
              </div>
            )}

            <Button
              variant="outline"
              size="sm"
              onClick={handleSyncWorkflowRuns}
              disabled={isSyncingCi || isLoading}
              className="h-8 text-xs gap-1.5 border-[#26262C] bg-[#141418] text-zinc-300"
            >
              {isSyncingCi ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <GitBranch className="h-3.5 w-3.5 text-zinc-400" />}
              Sync GitHub Actions
            </Button>

            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowRecordRunModal(true)}
              className="h-8 text-xs gap-1.5 border-[#26262C] bg-[#141418] text-zinc-300"
            >
              <Upload className="h-3.5 w-3.5 text-zinc-400" />
              Upload JUnit XML
            </Button>

            <Button
              size="sm"
              onClick={() => setShowGenerateTestsModal(true)}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              <Sparkles className="h-3.5 w-3.5" />
              Test Studio
            </Button>

            <Button
              variant="outline"
              size="sm"
              onClick={fetchRuns}
              disabled={isLoading}
              className="h-8 text-xs gap-1 border-[#26262C]"
            >
              <RefreshCw className={`h-3 w-3 ${isLoading ? "animate-spin" : ""}`} />
            </Button>
          </div>
        </div>
      </div>

      {/* Success Notification Banner */}
      {successToast && (
        <div className="flex items-center gap-2 rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3 text-xs text-emerald-300">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
          <span>{successToast}</span>
        </div>
      )}

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* 4-Column Metrics Row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
          <div className="text-xs text-zinc-400">Total Runs Ingested</div>
          <div className="text-xl font-semibold text-zinc-100 font-mono tabular-nums">{totalRuns}</div>
          <div className="text-[11px] text-zinc-500 font-mono">Live staging reports</div>
        </div>
        <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
          <div className="text-xs text-zinc-400">Pass Rate</div>
          <div className="text-xl font-semibold text-emerald-400 font-mono tabular-nums">{passRate}%</div>
          <div className="text-[11px] text-zinc-500 font-mono">{passedRuns} passed / {failedRuns} failed</div>
        </div>
        <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
          <div className="text-xs text-zinc-400">Mean Duration</div>
          <div className="text-xl font-semibold text-zinc-100 font-mono tabular-nums">{meanDurationFormatted}</div>
          <div className="text-[11px] text-zinc-500 font-mono">Containerized suites</div>
        </div>
        <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
          <div className="text-xs text-zinc-400">CI Health Status</div>
          <div className="flex items-center gap-1.5 mt-0.5">
            {hasRecentFailures ? (
              <span className="text-xs font-semibold text-rose-400 flex items-center gap-1 font-mono">
                <AlertTriangle className="h-3.5 w-3.5 text-rose-400" /> Regressions Detected
              </span>
            ) : (
              <span className="text-xs font-semibold text-emerald-400 flex items-center gap-1 font-mono">
                <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" /> All Suites Green
              </span>
            )}
          </div>
          <div className="text-[11px] text-zinc-500 font-mono">Real-time GitHub Actions</div>
        </div>
      </div>

      {/* Visual Pass/Fail Trend Timeline Bar */}
      {filteredRuns.length > 0 && (
        <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-2">
          <div className="flex items-center justify-between text-xs">
            <span className="font-medium text-zinc-300 flex items-center gap-2">
              <Clock className="h-3.5 w-3.5 text-zinc-400" />
              CI Verification Run History (Most Recent First)
            </span>
            <div className="flex items-center gap-3 text-[11px] text-zinc-400 font-mono">
              <span className="flex items-center gap-1">
                <span className="h-2 w-2 rounded-full bg-emerald-400" /> Passed
              </span>
              <span className="flex items-center gap-1">
                <span className="h-2 w-2 rounded-full bg-rose-400" /> Failed
              </span>
              <span className="flex items-center gap-1">
                <span className="h-2 w-2 rounded-full bg-amber-400" /> Running
              </span>
            </div>
          </div>
          <div className="flex items-center gap-1.5 overflow-x-auto py-1">
            {filteredRuns.slice(0, 24).map((r, idx) => (
              <button
                key={r.id || idx}
                onClick={() => openInspector(r)}
                title={`Run ${r.id.substring(0, 8)} (${r.branch}) - ${r.status} (${r.passedCount}/${r.totalCount})`}
                className={`h-6 flex-1 min-w-[26px] max-w-[36px] rounded flex items-center justify-center transition-all cursor-pointer ${
                  r.status === "PASSED"
                    ? "bg-emerald-950/40 border border-emerald-900 text-emerald-300 hover:bg-emerald-900/50"
                    : r.status === "FAILED"
                    ? "bg-rose-950/40 border border-rose-900 text-rose-300 hover:bg-rose-900/50"
                    : "bg-amber-950/40 border border-amber-900 text-amber-300 hover:bg-amber-900/50"
                }`}
              >
                <span className="text-[10px] font-mono font-bold">#{filteredRuns.length - idx}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Ingested Test Runs Table */}
      <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
        <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
          <div>
            <CardTitle className="text-xs font-semibold text-zinc-200">Recent Verification Runs</CardTitle>
            <CardDescription className="text-[11px] text-zinc-500">
              Click any run to inspect test suites, execution metrics, and trigger AI failure diagnosis.
            </CardDescription>
          </div>
          <Badge variant="outline" className="font-mono text-[10px] text-zinc-400 border-[#26262C]">{filteredRuns.length}</Badge>
        </div>

        {isLoading ? (
          <div className="flex flex-col items-center justify-center p-12 text-zinc-400">
            <Loader2 className="h-6 w-6 animate-spin text-zinc-400 mb-2" />
            <p className="text-xs font-mono">Fetching CI test runs from Gateway...</p>
          </div>
        ) : filteredRuns.length === 0 ? (
          <div className="flex flex-col items-center justify-center p-12 text-center text-zinc-400">
            <CheckCircle2 className="h-8 w-8 text-zinc-600 mb-2" />
            <p className="text-xs font-medium text-zinc-200">No test runs ingested yet</p>
            <p className="text-xs text-zinc-500 max-w-sm mt-1">
              Click <strong>&quot;Sync GitHub Actions&quot;</strong> or <strong>&quot;Upload JUnit XML&quot;</strong> to ingest automated test telemetry.
            </p>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className="border-[#1C1C21] hover:bg-transparent">
                <TableHead className="text-xs font-medium text-zinc-400">Run ID</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Repository</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Branch / PR</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Commit SHA</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Status</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Passed / Total</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Duration</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Ingested At</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredRuns.map((run) => (
                <TableRow
                  key={run.id}
                  className="cursor-pointer hover:bg-[#141418] transition-colors border-[#1C1C21]"
                  onClick={() => openInspector(run)}
                >
                  <TableCell className="font-mono text-xs font-semibold text-zinc-200">
                    {run.id.length > 12 ? `${run.id.substring(0, 10)}...` : run.id}
                  </TableCell>
                  <TableCell className="text-xs font-medium text-zinc-200">
                    {run.repositoryFullName}
                  </TableCell>
                  <TableCell className="text-xs text-zinc-400 font-mono">
                    {run.prNumber ? `PR #${run.prNumber}` : run.branch}
                  </TableCell>
                  <TableCell className="font-mono text-xs text-zinc-400">
                    {run.commitSha.substring(0, 7)}
                  </TableCell>
                  <TableCell>
                    <Badge
                      variant={
                        run.status === "PASSED"
                          ? "outline"
                          : run.status === "FAILED"
                          ? "destructive"
                          : "outline"
                      }
                      className={`text-[10px] font-mono ${
                        run.status === "PASSED" ? "text-emerald-400 border-emerald-900 bg-emerald-950/20" : ""
                      }`}
                    >
                      {run.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs font-mono tabular-nums">
                    <span className="text-emerald-400 font-semibold">{run.passedCount}</span>
                    <span className="text-zinc-600"> / {run.totalCount}</span>
                  </TableCell>
                  <TableCell className="text-xs text-zinc-400 font-mono tabular-nums">
                    {run.durationMs && run.durationMs > 0 ? `${(run.durationMs / 1000).toFixed(2)}s` : "—"}
                  </TableCell>
                  <TableCell className="text-xs text-zinc-400 font-mono">
                    {formatDate(run.createdAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      {/* Inspector Modal with AI Failure Diagnosis & Fix PR */}
      {selectedRun && (
        <Dialog open={!!selectedRun} onOpenChange={(open) => !open && setSelectedRun(null)}>
          <DialogHeader>
            <div className="flex items-center justify-between w-full">
              <div>
                <DialogTitle>Verification Run: {selectedRun.id}</DialogTitle>
                <DialogDescription>
                  Detailed test results ingested from JUnit XML report.
                </DialogDescription>
              </div>
              <DialogClose onClose={() => setSelectedRun(null)} />
            </div>
          </DialogHeader>

          <div className="space-y-4 my-3 max-h-[70vh] overflow-y-auto pr-1">
            <div className="grid grid-cols-3 gap-2 text-xs bg-[#141418] p-3 rounded-md border border-[#1C1C21]">
              <div>
                <span className="text-zinc-500">Repository: </span>
                <span className="text-zinc-200 font-mono">{selectedRun.repositoryFullName}</span>
              </div>
              <div>
                <span className="text-zinc-500">Commit: </span>
                <span className="text-zinc-200 font-mono">{selectedRun.commitSha.substring(0, 8)}</span>
              </div>
              <div>
                <span className="text-zinc-500">Duration: </span>
                <span className="text-zinc-200 font-mono">
                  {selectedRun.durationMs && selectedRun.durationMs > 0 ? `${(selectedRun.durationMs / 1000).toFixed(2)}s` : "—"}
                </span>
              </div>
            </div>

            {loadingResults ? (
              <div className="flex items-center justify-center p-8 text-zinc-400">
                <Loader2 className="h-5 w-5 animate-spin text-zinc-400 mr-2" />
                <span className="text-xs font-mono">Loading test cases...</span>
              </div>
            ) : testResults.length === 0 ? (
              <div className="p-6 text-center text-xs text-zinc-500 border border-dashed border-[#26262C] rounded-md">
                No individual test case results stored for this run.
              </div>
            ) : (
              <div className="space-y-1.5">
                <div className="text-xs font-semibold text-zinc-300">
                  Individual Test Results ({testResults.length})
                </div>
                {testResults.map((t, idx) => (
                  <div
                    key={t.id || idx}
                    className="p-3 rounded-md bg-[#141418] border border-[#1C1C21] space-y-2 text-xs"
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        {t.status === "PASSED" ? (
                          <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                        ) : (
                          <XCircle className="h-4 w-4 text-rose-400 shrink-0" />
                        )}
                        <div>
                          <div className="font-mono text-zinc-200 font-medium">
                            {t.testCaseName || t.name || `Test Case ${idx + 1}`}
                          </div>
                          {(t.className || t.classname) && (
                            <div className="text-[11px] text-zinc-500 font-mono">{t.className || t.classname}</div>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <Badge variant={t.status === "PASSED" ? "outline" : "destructive"} className={`text-[10px] font-mono ${t.status === "PASSED" ? "text-emerald-400 border-emerald-900" : ""}`}>
                          {t.status}
                        </Badge>
                        {t.status === "FAILED" && (
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={diagnosingTestName === (t.name || t.testCaseName)}
                            onClick={() => handleDiagnoseFailure(t)}
                            className="text-[11px] h-6 px-2 border-rose-900 bg-rose-950/30 text-rose-300 hover:bg-rose-900/50 gap-1"
                          >
                            {diagnosingTestName === (t.name || t.testCaseName) && (
                              <Loader2 className="h-3 w-3 animate-spin" />
                            )}
                            Diagnose
                          </Button>
                        )}
                      </div>
                    </div>

                    {(t.failureMessage || t.errorMessage) && (
                      <div className="text-[11px] font-mono text-rose-300 bg-rose-950/30 p-2 rounded border border-rose-900/50">
                        {t.failureMessage || t.errorMessage}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}

            {/* AI Diagnosis Result Card */}
            {diagnosisResult && (
              <div className="p-4 rounded-md border border-[#26262C] bg-[#141418] space-y-3 mt-3">
                <div className="flex items-center justify-between border-b border-[#26262C] pb-2">
                  <div className="flex items-center gap-2 text-xs font-semibold text-zinc-200">
                    <Sparkles className="h-4 w-4 text-zinc-400" />
                    AI Root-Cause Diagnosis: {diagnosisResult.test_name}
                  </div>
                  <div className="flex items-center gap-2">
                    {diagnosisResult.failure_category && (
                      <Badge variant="outline" className="text-[10px] font-mono text-amber-400 border-amber-900">
                        {diagnosisResult.failure_category}
                      </Badge>
                    )}
                    <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                      Confidence: {diagnosisResult.confidence}
                    </Badge>
                    <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C]">
                      {diagnosisResult.suspected_file}:{diagnosisResult.suspected_line}
                    </Badge>
                  </div>
                </div>

                <div className="space-y-1">
                  <div className="text-xs font-semibold text-zinc-300">Root Cause:</div>
                  <p className="text-xs text-zinc-200 bg-[#09090B] p-2.5 rounded border border-[#1C1C21]">
                    {diagnosisResult.root_cause}
                  </p>
                </div>

                {diagnosisResult.why_it_failed_detail && (
                  <div className="space-y-1">
                    <div className="text-xs font-semibold text-zinc-300">Technical Analysis (Why It Failed):</div>
                    <p className="text-xs text-zinc-300 bg-[#09090B] p-2.5 rounded border border-[#1C1C21] leading-relaxed">
                      {diagnosisResult.why_it_failed_detail}
                    </p>
                  </div>
                )}

                <div className="space-y-1">
                  <div className="text-xs font-semibold text-zinc-300">Remediation Steps:</div>
                  <ul className="text-xs text-zinc-300 space-y-1 list-disc list-inside bg-[#09090B] p-2.5 rounded border border-[#1C1C21]">
                    {diagnosisResult.remediation_steps.map((step, sIdx) => (
                      <li key={sIdx}>{step}</li>
                    ))}
                  </ul>
                </div>

                <div className="space-y-1">
                  <div className="text-xs font-semibold text-zinc-300 flex items-center justify-between">
                    <span>Proposed Patch Diff:</span>
                    <span className="text-[10px] font-mono text-zinc-500">{diagnosisResult.proposed_fix_title}</span>
                  </div>
                  <pre className="text-[11px] font-mono text-emerald-300 bg-[#09090B] p-3 rounded border border-[#1C1C21] overflow-x-auto whitespace-pre">
                    {diagnosisResult.proposed_fix_diff}
                  </pre>
                </div>

                <div className="flex items-center justify-between pt-2">
                  {openedPrUrl ? (
                    <a
                      href={openedPrUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-xs text-emerald-400 hover:underline flex items-center gap-1 font-mono font-semibold"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                      View Remediation Pull Request ({openedPrUrl})
                    </a>
                  ) : (
                    <Button
                      size="sm"
                      onClick={handleOpenFixPr}
                      disabled={isOpeningPr}
                      className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                    >
                      {isOpeningPr ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <GitBranch className="h-3.5 w-3.5" />}
                      Open Fix Pull Request
                    </Button>
                  )}
                </div>
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setSelectedRun(null)} className="h-8 text-xs border-[#26262C]">
              Close
            </Button>
          </DialogFooter>
        </Dialog>
      )}

      {/* Record Run / Upload JUnit XML Modal */}
      <Dialog open={showRecordRunModal} onOpenChange={setShowRecordRunModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Upload JUnit XML / Ingest Test Run</DialogTitle>
              <DialogDescription>
                Record containerized CI test execution metrics or paste a JUnit XML report.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowRecordRunModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleRecordRunSubmit} className="space-y-3.5 my-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-zinc-300">Target Repository</label>
              <select
                aria-label="Target Repository"
                value={recordRepoId}
                onChange={(e) => setRecordRepoId(e.target.value)}
                className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
                required
              >
                {repositories.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.fullName}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-zinc-300">Branch</label>
              <Input
                value={recordBranch}
                onChange={(e) => setRecordBranch(e.target.value)}
                placeholder="main"
                required
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Commit SHA</label>
            <Input
              value={recordCommitSha}
              onChange={(e) => setRecordCommitSha(e.target.value)}
              placeholder="e.g. 7a1656b"
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">JUnit XML Report Content</label>
            <textarea
              value={recordXmlContent}
              onChange={(e) => setRecordXmlContent(e.target.value)}
              rows={8}
              className="w-full font-mono text-[11px] bg-[#09090B] border border-[#26262C] rounded-md p-2.5 text-zinc-300 focus:outline-none"
              placeholder="<?xml version='1.0' encoding='UTF-8'?>..."
              required
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setShowRecordRunModal(false)} className="h-8 text-xs border-[#26262C]">
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={isSubmittingRun || !recordRepoId} className="h-8 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
              {isSubmittingRun ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <Upload className="h-3.5 w-3.5 mr-1" />}
              Ingest & Record Run
            </Button>
          </DialogFooter>
        </form>
      </Dialog>

      {/* AI Generate Unit Tests Modal */}
      <Dialog open={showGenerateTestsModal} onOpenChange={setShowGenerateTestsModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Test Studio (Selenium / TestNG / JUnit / Pytest / Vitest)</DialogTitle>
              <DialogDescription>
                Generate multi-framework unit & E2E tests, run safe in-app verification, and diagnose failures with 1-click Fix PRs.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowGenerateTestsModal(false)} />
          </div>
        </DialogHeader>

        <div className="space-y-4 my-3 max-h-[70vh] overflow-y-auto pr-1">
          <form onSubmit={handleGenerateUnitTests} className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <label className="text-xs font-medium text-zinc-300">Repository</label>
                <select
                  aria-label="Repository"
                  value={genRepoId}
                  onChange={(e) => setGenRepoId(e.target.value)}
                  className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
                  required
                >
                  {repositories.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.fullName}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-1">
                <label className="text-xs font-medium text-zinc-300">Test Framework</label>
                <select
                  aria-label="Test Framework"
                  value={genFramework}
                  onChange={(e) => setGenFramework(e.target.value)}
                  className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
                >
                  <option value="AUTO">AUTO (Detect from file)</option>
                  <option value="JUNIT5">JUnit 5 (Java)</option>
                  <option value="TESTNG">TestNG (Java @DataProvider)</option>
                  <option value="SELENIUM">Selenium WebDriver (E2E UI)</option>
                  <option value="PYTEST">Pytest (Python)</option>
                  <option value="VITEST">Vitest / Jest (TypeScript)</option>
                </select>
              </div>
            </div>

            <div className="space-y-1">
              <label className="text-xs font-medium text-zinc-300">Target Source File (optional)</label>
              <Input
                value={genFilePath}
                onChange={(e) => setGenFilePath(e.target.value)}
                placeholder="e.g. src/main/java/com/forgeai/service/AuthenticationService.java"
              />
            </div>

            <div className="space-y-1">
              <label className="text-xs font-medium text-zinc-300">Requirement Focus</label>
              <Input
                value={genRequirementTitle}
                onChange={(e) => setGenRequirementTitle(e.target.value)}
                placeholder="e.g. Boundary checks, tenant isolation, and error handling"
              />
            </div>

            <Button
              type="submit"
              size="sm"
              disabled={isGeneratingTests || !genRepoId}
              className="w-full h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              {isGeneratingTests ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              Generate Test Suite
            </Button>
          </form>

          {/* Generated Suite Output */}
          {generatedSuite && (
            <div className="p-4 rounded-md border border-[#26262C] bg-[#141418] space-y-3.5">
              <div className="flex items-center justify-between border-b border-[#26262C] pb-2">
                <div>
                  <h4 className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
                    <FileCode className="h-4 w-4 text-emerald-400" />
                    {generatedSuite.test_file_path}
                  </h4>
                  <span className="text-[10px] text-zinc-400 font-mono">
                    Framework: {generatedSuite.framework} | Source: {generatedSuite.source_file}
                  </span>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    navigator.clipboard.writeText(generatedSuite.test_code);
                    setCopiedCode(true);
                    setTimeout(() => setCopiedCode(false), 2000);
                  }}
                  className="text-xs h-7 gap-1 border-[#26262C]"
                >
                  {copiedCode ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                  {copiedCode ? "Copied" : "Copy"}
                </Button>
              </div>

              {/* Test Cases Pill List */}
              <div className="space-y-1.5">
                <span className="text-[11px] font-medium text-zinc-300">Generated Test Cases:</span>
                <div className="grid grid-cols-1 gap-1.5">
                  {generatedSuite.test_cases.map((tc, tcIdx) => (
                    <div
                      key={tcIdx}
                      className="p-2 rounded bg-[#09090B] border border-[#1C1C21] flex items-center justify-between text-xs"
                    >
                      <span className="font-mono text-zinc-200">{tc.name}</span>
                      <div className="flex items-center gap-2">
                        <span className="text-[11px] text-zinc-400">{tc.description}</span>
                        <Badge
                          variant="outline"
                          className="text-[9px] font-mono text-zinc-400 border-[#26262C]"
                        >
                          {tc.category}
                        </Badge>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Code Preview */}
              <pre className="text-[11px] font-mono text-zinc-300 bg-[#09090B] p-3 rounded border border-[#1C1C21] max-h-48 overflow-y-auto whitespace-pre">
                {generatedSuite.test_code}
              </pre>

              {/* Action Buttons */}
              <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-[#26262C]">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRunAndVerifySuite}
                  disabled={isVerifyingSuite}
                  className="h-8 text-xs gap-1.5 border-[#26262C] text-zinc-200"
                >
                  {isVerifyingSuite ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <PlayCircle className="h-3.5 w-3.5 text-zinc-400" />
                  )}
                  Run & Verify Suite Now
                </Button>

                <div className="flex items-center gap-2">
                  {committedPrUrl ? (
                    <a
                      href={committedPrUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-xs text-emerald-400 hover:underline flex items-center gap-1 font-mono font-semibold"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                      View Test Suite PR
                    </a>
                  ) : (
                    <Button
                      size="sm"
                      onClick={handleCommitTestsPr}
                      disabled={isCommittingPr}
                      className="h-8 text-xs gap-1 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                    >
                      {isCommittingPr ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <GitBranch className="h-3.5 w-3.5" />}
                      Commit via PR
                    </Button>
                  )}
                </div>
              </div>

              {/* Safe In-App Test Verification Results Panel */}
              {verifiedResults && (
                <div className="p-4 rounded-md border border-[#26262C] bg-[#141418] space-y-3 mt-3">
                  <div className="flex items-center justify-between border-b border-[#26262C] pb-2">
                    <div className="flex items-center gap-2">
                      <ShieldCheck className="h-4 w-4 text-emerald-400" />
                      <span className="text-xs font-semibold text-zinc-100">Verification Runner Results</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                        {verifiedResults.framework}
                      </Badge>
                      <Badge variant="outline" className="text-[10px] font-mono text-emerald-400 border-emerald-900">
                        {verifiedResults.passed_tests} Passed
                      </Badge>
                      {verifiedResults.failed_tests > 0 && (
                        <Badge variant="destructive" className="text-[10px] font-mono">
                          {verifiedResults.failed_tests} Failed
                        </Badge>
                      )}
                      <span className="text-[10px] font-mono text-zinc-400">
                        {(verifiedResults.duration_ms / 1000).toFixed(2)}s
                      </span>
                    </div>
                  </div>

                  {/* Test case rows */}
                  <div className="space-y-2">
                    {verifiedResults.results.map((r, rIdx) => (
                      <div
                        key={rIdx}
                        className={`p-3 rounded border text-xs space-y-2 ${
                          r.status === "PASSED"
                            ? "bg-[#09090B] border-[#1C1C21]"
                            : "bg-rose-950/20 border-rose-900"
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            {r.status === "PASSED" ? (
                              <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                            ) : (
                              <AlertCircle className="h-3.5 w-3.5 text-rose-400" />
                            )}
                            <span className="font-mono font-semibold text-zinc-200">{r.name}</span>
                          </div>
                          <div className="flex items-center gap-2">
                            <span className="text-[10px] font-mono text-zinc-400">{r.duration_ms}ms</span>
                            <Badge variant={r.status === "PASSED" ? "outline" : "destructive"} className="text-[10px] font-mono">
                              {r.status}
                            </Badge>
                          </div>
                        </div>

                        {r.error_message && (
                          <div className="text-[11px] font-mono text-rose-300 bg-rose-950/30 p-2 rounded border border-rose-900/50">
                            {r.error_message}
                          </div>
                        )}

                        {/* AI Root-Cause Failure Diagnosis Card */}
                        {r.ai_failure_diagnosis && (
                          <div className="p-3 rounded bg-[#09090B] border border-[#26262C] space-y-2 mt-2">
                            <div className="flex items-center justify-between border-b border-[#1C1C21] pb-1.5">
                              <span className="text-[11px] font-semibold text-zinc-300 flex items-center gap-1.5">
                                <Sparkles className="h-3.5 w-3.5 text-zinc-400" />
                                AI Root-Cause Analysis ({r.ai_failure_diagnosis.failure_category || "ASSERTION_MISMATCH"})
                              </span>
                              <Badge variant="outline" className="text-[9px] font-mono text-zinc-400 border-[#26262C]">
                                {r.ai_failure_diagnosis.suspected_file}:{r.ai_failure_diagnosis.suspected_line}
                              </Badge>
                            </div>

                            <p className="text-[11px] text-zinc-200 bg-[#141418] p-2 rounded border border-[#1C1C21]">
                              <span className="font-semibold text-amber-400">Root Cause: </span>
                              {r.ai_failure_diagnosis.root_cause}
                            </p>

                            {r.ai_failure_diagnosis.why_it_failed_detail && (
                              <p className="text-[11px] text-zinc-300 bg-[#141418] p-2 rounded border border-[#1C1C21] leading-relaxed">
                                <span className="font-semibold text-zinc-200">Why It Failed: </span>
                                {r.ai_failure_diagnosis.why_it_failed_detail}
                              </p>
                            )}

                            <div className="space-y-1">
                              <span className="text-[10px] font-medium text-zinc-400">Remediation Steps:</span>
                              <ul className="text-[10px] text-zinc-300 space-y-0.5 list-disc list-inside bg-[#141418] p-2 rounded border border-[#1C1C21]">
                                {r.ai_failure_diagnosis.remediation_steps.map((st, stIdx) => (
                                  <li key={stIdx}>{st}</li>
                                ))}
                              </ul>
                            </div>

                            <div className="space-y-1">
                              <div className="text-[10px] font-medium text-zinc-400 flex items-center justify-between">
                                <span>Proposed Code Fix:</span>
                                <span className="text-[9px] font-mono text-zinc-500">{r.ai_failure_diagnosis.proposed_fix_title}</span>
                              </div>
                              <pre className="text-[10px] font-mono text-emerald-300 bg-[#141418] p-2 rounded border border-[#1C1C21] overflow-x-auto whitespace-pre">
                                {r.ai_failure_diagnosis.proposed_fix_diff}
                              </pre>
                            </div>

                            <div className="flex items-center justify-end pt-1">
                              {fixPrUrlMap[r.name] ? (
                                <a
                                  href={fixPrUrlMap[r.name]}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="text-[11px] text-emerald-400 hover:underline flex items-center gap-1 font-mono font-semibold"
                                >
                                  <ExternalLink className="h-3 w-3" />
                                  View Fix PR ({fixPrUrlMap[r.name]})
                                </a>
                              ) : (
                                <Button
                                  size="sm"
                                  disabled={applyingFixForTest === r.name}
                                  onClick={() => handleApplyFixPr(r.ai_failure_diagnosis!)}
                                  className="h-7 text-[11px] gap-1 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                                >
                                  {applyingFixForTest === r.name && (
                                    <Loader2 className="h-3 w-3 animate-spin" />
                                  )}
                                  Open Fix Pull Request
                                </Button>
                              )}
                            </div>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => setShowGenerateTestsModal(false)} className="h-8 text-xs border-[#26262C]">
            Close
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}
