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
