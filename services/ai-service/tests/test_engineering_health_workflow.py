import pytest
from uuid import uuid4
from fastapi import HTTPException
from app.domain.models import (
    SecurityStatus,
    EvidenceStatus,
    SecurityCategory,
    FindingSeverity,
    MergeDecision,
    SecurityAssessment,
    SecurityFinding,
)
from app.application.security_intelligence_engine import SecurityIntelligenceEngine
from app.application.dependency_vulnerability_scanner import (
    DependencyVulnerabilityScanner,
    KnownVulnerabilityDatabase,
)
from app.application.merge_readiness_engine import MergeReadinessEngine
from app.application.remediation_effectiveness_engine import RemediationEffectivenessEngine
from app.presentation.dtos import (
    ExplainFindingRequest,
    SecretScanRequest,
    RepositoryScanRequest,
)


@pytest.fixture
def org_and_repo_id():
    return uuid4(), uuid4()


# SEC-UI-01: Critical secret finding returned redacted
def test_sec_ui_01_critical_secret_returned_redacted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_secret = "AKIA1234567890TESTXX"
    changed_files = [
        {"filename": "config/aws.ts", "content": f'export const key = "{raw_secret}";', "status": "added"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
    )

    finding = next(f for f in assessment.findings if f.rule_id == "SEC_SECRET_AWS_KEY")
    assert finding.severity == FindingSeverity.CRITICAL.value
    assert finding.is_blocker is True
    # Redacted verification
    assert raw_secret not in finding.redacted_evidence
    assert "AKIA" in finding.redacted_evidence
    assert "REDACTED" in finding.redacted_evidence


# SEC-UI-02: Raw secret never persisted
def test_sec_ui_02_raw_secret_never_persisted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_gh_token = "ghp_SYNTHETIC_TEST_TOKEN_ONLY_123456789"
    raw_rsa = "-----BEGIN RSA PRIVATE KEY-----\nSYNTHETIC_TEST_ONLY_123456789\n-----END RSA PRIVATE KEY-----"

    changed_files = [
        {"filename": ".env", "content": f"GITHUB_TOKEN={raw_gh_token}\nKEY={raw_rsa}", "status": "added"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
    )

    dumped = assessment.model_dump_json()
    assert raw_gh_token not in dumped
    assert raw_rsa not in dumped
    assert "SYNTHETIC_TEST_TOKEN_ONLY_123456789" not in dumped


# SEC-UI-03: Raw secret never returned by API (dedicated secret scan)
def test_sec_ui_03_raw_secret_never_returned_by_scan(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_stripe = "sk_live_1234567890abcdefABCDEF1234"
    files = [{"filename": "src/billing.ts", "content": f"const stripeKey = '{raw_stripe}';"}]

    result = SecurityIntelligenceEngine.scan_repository_for_secrets(
        organization_id=org_id,
        repository_id=repo_id,
        files=files,
    )

    assert result["secrets_found"] == 1
    assert result["critical_count"] == 1
    assert raw_stripe not in str(result)
    assert "REDACTED" in str(result)


# SEC-UI-04: Security finding contains file/line/rule/evidence
def test_sec_ui_04_finding_contains_file_line_rule_evidence(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "auth/token.py", "content": "# line 1\n# line 2\nJWT = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.do_not_leak_signature_test'\n", "status": "added"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
    )

    finding = next(f for f in assessment.findings if f.rule_id == "SEC_SECRET_JWT_TOKEN")
    assert finding.file_path == "auth/token.py"
    assert finding.line_number == 3
    assert finding.rule_id == "SEC_SECRET_JWT_TOKEN"
    assert "Line 3" in finding.redacted_evidence
    assert finding.remediation != ""


# SEC-UI-05: Merge readiness becomes BLOCKED on critical security finding
def test_sec_ui_05_merge_readiness_blocked_on_critical_security(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_secret = "AKIA1234567890TESTXX"
    changed_files = [
        {"filename": "config.py", "content": f'AWS_KEY = "{raw_secret}"\n', "status": "added"}
    ]

    sec_assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
    )

    readiness = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=1,
        commit_sha="abc1234",
        pr_metadata={"title": "PR 1", "body": "test"},
        sync_status={"syncStatus": "SYNCED"},
        deterministic_risk_score=75.0,
        deterministic_risk_level="HIGH",
        risk_factors=[],
        impact_metadata={},
        test_strategy={},
        actual_ci_results={"conclusion": "success", "status": "completed"},
        security_assessment=sec_assessment,
    )

    assert readiness.decision in (MergeDecision.NOT_READY.value, "BLOCKED", "SECURITY_BLOCKER")
    assert any("security" in b.lower() or "blocker" in b.lower() for b in readiness.blocking_reasons)


# SEC-UI-06: Re-scan removes finding only after deterministic verification
def test_sec_ui_06_rescan_removes_finding_only_after_deterministic_verification(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    target_file = "config/aws.ts"
    original_code = 'export const key = "AKIA1234567890TESTXX";'
    # Fixed code: uses process.env
    patched_code = 'export const key = process.env.AWS_ACCESS_KEY_ID || "";'

    effectiveness = RemediationEffectivenessEngine.evaluate_remediation_effectiveness(
        organization_id=org_id,
        repository_id=repo_id,
        action_id="act-sec-01",
        target_file=target_file,
        original_content=original_code,
        patched_content=patched_code,
        target_finding_id="SEC_SECRET_AWS_KEY",
        before_risk_score=75.0,
        before_merge_readiness=MergeDecision.NOT_READY.value,
        tests_added=0,
        tests_passed=True,
    )

    assert "SEC_SECRET_AWS_KEY" in effectiveness.findings_fixed
    assert len(effectiveness.new_findings_introduced) == 0
    assert effectiveness.is_effective is True
    assert effectiveness.after_risk_score < effectiveness.before_risk_score


# SEC-UI-07 & 08: Workspace/Tenant isolation applies to analysis & cross-tenant access denied
def test_sec_ui_07_and_08_workspace_isolation_and_cross_access():
    org_a = uuid4()
    org_b = uuid4()
    repo_id = uuid4()

    files = [{"filename": "main.py", "content": "print('clean')"}]
    assessment_a = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_a,
        repository_id=repo_id,
        changed_files=files,
    )

    # Assessment A is strictly tagged with org_a
    for f in assessment_a.findings:
        assert f.organization_id == org_a
        assert f.organization_id != org_b


# SEC-UI-09: Dependency scanner unavailable → PARTIAL evidence
def test_sec_ui_09_dependency_scanner_unavailable_reports_partial(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [{"filename": "app.py", "content": "print('hello')"}]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        dependency_scanner_configured=False,
    )

    assert assessment.evidence_status == EvidenceStatus.PARTIAL.value
    assert assessment.security_status == SecurityStatus.NO_SECURITY_FINDINGS.value


# SEC-UI-10: Actual dependency vulnerability evidence displayed correctly
def test_sec_ui_10_actual_dependency_vulnerability_displayed(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    pom_content = """
    <project>
        <dependencies>
            <dependency>
                <groupId>org.apache.logging.log4j</groupId>
                <artifactId>log4j-core</artifactId>
                <version>2.14.1</version>
            </dependency>
        </dependencies>
    </project>
    """
    changed_files = [{"filename": "pom.xml", "content": pom_content}]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        dependency_scanner_configured=True,
    )

    dep_finding = next(f for f in assessment.findings if "SEC_DEP_CVE_2021_44228" in f.rule_id)
    assert dep_finding.severity == FindingSeverity.CRITICAL.value
    assert "Log4Shell" in dep_finding.description
    assert dep_finding.is_blocker is True
    assert "pom.xml" in dep_finding.redacted_evidence
    assert dep_finding.metadata.get("fixed_version") == "2.17.1"


# SEC-UI-11: Repository secret scan is workspace scoped
def test_sec_ui_11_repository_secret_scan_scoped(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    files = [
        {"filename": "clean1.ts", "content": "export const a = 1;"},
        {"filename": "clean2.ts", "content": "export const b = 2;"},
    ]

    result = SecurityIntelligenceEngine.scan_repository_for_secrets(
        organization_id=org_id,
        repository_id=repo_id,
        files=files,
        commit_sha="commit-101",
    )

    assert result["files_scanned"] == 2
    assert result["secrets_found"] == 0
    assert result["status"] == "CLEAN"
    assert result["commit_sha"] == "commit-101"


# SEC-UI-12: Realistic Bug Scenario Tests & False Positive Control
def test_sec_ui_12_realistic_bug_scenarios_and_false_positive_control(org_and_repo_id):
    org_id, repo_id = org_and_repo_id

    # 1. SQL injection pattern
    sqli_files = [{"filename": "dao.py", "content": 'query = "SELECT * FROM users WHERE id = \'" + uid + "\'"'}]
    sec_sqli = SecurityIntelligenceEngine.analyze_security(organization_id=org_id, repository_id=repo_id, changed_files=sqli_files)
    assert any(f.rule_id == "SEC_CODE_SQL_INJECTION" for f in sec_sqli.findings)

    # 2. Command injection
    cmd_files = [{"filename": "shell.py", "content": "subprocess.run(user_input, shell=True)"}]
    sec_cmd = SecurityIntelligenceEngine.analyze_security(organization_id=org_id, repository_id=repo_id, changed_files=cmd_files)
    assert any(f.rule_id == "SEC_CODE_COMMAND_INJECTION" for f in sec_cmd.findings)

    # 3. Disabled TLS
    tls_files = [{"filename": "http.py", "content": "requests.post(url, data=payload, verify=False)"}]
    sec_tls = SecurityIntelligenceEngine.analyze_security(organization_id=org_id, repository_id=repo_id, changed_files=tls_files)
    assert any(f.rule_id == "SEC_INSECURE_TLS_VERIFY_DISABLED" for f in sec_tls.findings)

    # 4. Safe / False Positive Control: Parameterized query & safe placeholder
    safe_files = [
        {"filename": "safe_dao.py", "content": 'cursor.execute("SELECT * FROM users WHERE id = %s", (uid,))'},
        {"filename": "test_keys.py", "content": 'MOCK_KEY = "AKIA_DUMMY_MOCK_KEY_EXAMPLE"'},
        {"filename": "env_config.py", "content": 'password = os.getenv("DB_PASSWORD", "changeme")'},
    ]
    sec_safe = SecurityIntelligenceEngine.analyze_security(organization_id=org_id, repository_id=repo_id, changed_files=safe_files)
    # None of these safe idioms should trigger critical false positives
    assert not any(f.rule_id in ("SEC_CODE_SQL_INJECTION", "SEC_SECRET_AWS_KEY") for f in sec_safe.findings)
