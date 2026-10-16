export interface User {
  id: string;
  email: string;
  fullName: string;
  avatarUrl?: string;
  roles: string[];
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface AuthResponse {
  tokens: AuthTokens;
  user: User;
}

export interface Organization {
  id: string;
  name: string;
  slug: string;
  role: "ADMIN" | "MAINTAINER" | "DEVELOPER" | "VIEWER";
  avatarUrl?: string;
  projectsCount?: number;
}

export type TaskStatus = "TODO" | "IN_PROGRESS" | "IN_REVIEW" | "DONE";
export type TaskPriority = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export interface Requirement {
  id: string;
  projectId: string;
  reqKey: string; // e.g. REQ-501
  title: string;
  description: string;
  category: "SECURITY" | "FUNCTIONAL" | "PERFORMANCE" | "COMPLIANCE";
  status: "DRAFT" | "ACTIVE" | "VERIFIED";
  createdAt: string;
}

export interface Task {
  id: string;
  projectId: string;
  taskKey: string; // e.g. TASK-201
  requirementKey?: string;
  title: string;
  description: string;
  status: TaskStatus;
  priority: TaskPriority;
  assigneeName?: string;
  assigneeAvatar?: string;
  branchName?: string;
  linkedPrNumber?: number;
  updatedAt: string;
}

export interface Project {
  id: string;
  organizationId: string;
  key: string;
  name: string;
  description: string;
  repositoryName: string;
  repositoryUrl: string;
  requirementCount: number;
  taskCount: number;
  healthScore: number;
  updatedAt: string;
}

export interface Repository {
  id: string;
  organizationId: string;
  name: string;
  fullName: string; // e.g. forgeai/governance-core
  defaultBranch: string;
  isPrivate: boolean;
  webhookInstalled: boolean;
  openPullRequestsCount: number;
  lastSyncAt: string;
}

export interface PullRequest {
  id: string;
  repositoryId: string;
  number: number;
  title: string;
  author: string;
  headBranch: string;
  baseBranch: string;
  headSha: string;
  state: "OPEN" | "MERGED" | "CLOSED";
  testRunStatus?: "PASSED" | "FAILED" | "RUNNING";
  riskLevel?: "LOW" | "MEDIUM" | "HIGH";
  updatedAt: string;
}

export interface TestCase {
  id: string;
  name: string;
  className: string;
  status: "PASSED" | "FAILED" | "SKIPPED";
  durationSeconds: number;
  failureMessage?: string;
  stackTrace?: string;
}

export interface TestSuite {
  id: string;
  name: string;
  tests: number;
  passed: number;
  failures: number;
  skipped: number;
  durationSeconds: number;
  cases: TestCase[];
}

export interface TestRun {
  id: string;
  repositoryFullName: string;
  commitSha: string;
  branch: string;
  prNumber?: number;
  status: "PASSED" | "FAILED" | "RUNNING" | "QUEUED";
  passedCount: number;
  failedCount: number;
  skippedCount: number;
  totalCount: number;
  durationMs: number;
  createdAt: string;
  suites?: TestSuite[];
}

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

export interface SymbolImpact {
  name: string;
  kind: "CLASS" | "METHOD" | "ENDPOINT" | "DATABASE_SCHEMA";
  filePath: string;
  impactScore: number;
  directDependency: boolean;
}

export interface GraphEdge {
  from: string;
  to: string;
  relation: "CALLS" | "EXTENDS" | "READS" | "MUTATES";
}

export interface AnalysisAudit {
  id: string;
  pullRequestId: number;
  prTitle: string;
  commitSha: string;
  modelName: string;
  promptVersion: string;
  riskLevel: RiskLevel;
  blastRadiusScore: number; // 0-100
  summary: string;
  recommendations: string[];
  affectedSymbols: SymbolImpact[];
  graphEdges: GraphEdge[];
  createdAt: string;
}

export interface TraceabilityNode {
  requirement: {
    key: string;
    title: string;
    status: "ACTIVE" | "VERIFIED" | "DRAFT";
  };
  task: {
    key: string;
    title: string;
    status: TaskStatus;
    priority: TaskPriority;
  };
  sourceControl: {
    prNumber: number;
    prTitle: string;
    commitSha: string;
    author: string;
  };
  verification: {
    testRunId: string;
    status: "PASSED" | "FAILED" | "RUNNING";
    passedTests: number;
    totalTests: number;
  };
  aiBlastRadius: {
    riskLevel: RiskLevel;
    score: number;
    affectedSymbolsCount: number;
    flaggedRisks: string[];
  };
}

export interface GitHubHealth {
  configured: boolean;
  appId?: number | null;
  privateKeyPresent?: boolean;
  webhookSecretPresent?: boolean;
  status: "CONFIGURED" | "UNCONFIGURED";
  totalInstallations?: number;
  activeInstallations?: number;
  quarantinedInstallations?: number;
  quarantineWarning?: string;
}

export interface GitHubInstallation {
  id: string;
  organizationId: string;
  installationId: number;
  accountLogin: string;
  accountType: string;
  status: "ACTIVE" | "SUSPENDED" | "DELETED";
}

export interface AvailableRepository {
  id: number;
  installationId: number;
  name: string;
  fullName: string;
  isPrivate: boolean;
  defaultBranch: string;
  ownerLogin: string;
}

export type ProjectHealthStatus = "GREEN" | "AMBER" | "RED";

export interface ProjectHealth {
  status: ProjectHealthStatus;
  healthScore: number;
  totalRequirements: number;
  requirementsCompleted: number;
  requirementsAtRisk: number;
  requirementsWithoutImplementation: number;
  requirementsWithoutTests: number;
  openTasks: number;
  blockedTasks: number;
  activePrs: number;
  failingPrs: number;
  mergeReadinessBlockers: number;
  unresolvedSecurityFindings: number;
  unresolvedReliabilityFindings: number;
  activeRemediationActions: number;
  evidenceStatus?: "AVAILABLE" | "PARTIAL" | "UNAVAILABLE";
  reasons: string[];
}

export interface TraceabilityGap {
  gapType: string;
  artifactId: string;
  artifactName: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
  evidence: string;
  recommendation: string;
}

export interface RequirementHealth {
  requirementId: string;
  identifier: string;
  title: string;
  status: "NOT_STARTED" | "IN_PROGRESS" | "IMPLEMENTED" | "VALIDATED" | "AT_RISK" | "COMPLETED";
  implementationStatus: string;
  testStatus: string;
  riskStatus: string;
  traceabilityStatus: string;
  completenessScore: number;
  linkedTasksCount: number;
  linkedPrsCount: number;
  testsCount: number;
  activeRiskScore?: number;
  remediationCount: number;
}

export interface TraceabilitySpineChain {
  requirement: {
    id: string;
    identifier: string;
    title: string;
    description?: string;
    status: string;
    version: number;
  };
  tasks: Array<{
    id: string;
    taskKey: string;
    title: string;
    status: TaskStatus;
    priority: TaskPriority;
  }>;
  commits: Array<{
    id?: string;
    commitSha: string;
    message: string;
    authorName?: string;
  }>;
  pullRequests: Array<{
    id?: string;
    prNumber: number;
    title: string;
    state: string;
    headSha: string;
    isMerged?: boolean;
  }>;
  testRuns: Array<{
    id?: string;
    status: string;
    totalTests?: number;
    passedTests?: number;
    failedTests?: number;
  }>;
  testCases: Array<{
    testCaseId?: string;
    testName: string;
    classname: string;
    latestStatus: string;
  }>;
  aiAnalyses: Array<{
    id?: string;
    riskLevel?: string;
    riskScore?: number;
    summary?: string;
    mergeReadiness?: Record<string, any>;
  }>;
  remediationActions: Array<{
    id?: string;
    actionId?: string;
    title?: string;
    status?: string;
    severity?: string;
  }>;
  health?: RequirementHealth;
  gaps?: TraceabilityGap[];
}

export interface ProjectTraceabilityResponse {
  projectId: string;
  organizationId: string;
  health: ProjectHealth;
  gaps: TraceabilityGap[];
  chains: TraceabilitySpineChain[];
  repositories: Array<{
    id: string;
    repositoryId: string;
    repositoryName: string;
    createdAt: string;
  }>;
  links: Array<{
    id: string;
    sourceType: string;
    sourceId: string;
    targetType: string;
    targetId: string;
    linkOrigin: string;
    confidence: string;
    evidence?: string;
  }>;
  generatedAt: string;
}

export interface TraceabilitySnapshot {
  id: string;
  organizationId: string;
  projectId: string;
  snapshotName: string;
  prNumber?: number;
  commitSha?: string;
  repositoryId?: string;
  snapshotData: string;
  createdAt: string;
}


