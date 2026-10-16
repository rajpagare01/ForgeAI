import pytest
from uuid import uuid4
from app.domain.models import (
    SecurityStatus,
    EvidenceStatus,
    SecurityCategory,
    FindingSeverity,
    RepositoryDependency,
    DependencyType,
    DependencyConfidence,
)
from app.application.security_intelligence_engine import SecurityIntelligenceEngine


@pytest.fixture
def org_and_repo_id():
    return uuid4(), uuid4()


def test_private_key_secret_detected_and_redacted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----"
    changed_files = [
        {"filename": "config/keys.pem", "content": raw_key, "status": "added"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    assert assessment.security_status == SecurityStatus.SECURITY_BLOCKER.value
    assert assessment.critical_count >= 1
    assert assessment.security_risk_score >= 40.0

    key_finding = next(f for f in assessment.findings if f.rule_id == "SEC_SECRET_PRIVATE_KEY")
    assert key_finding.severity == FindingSeverity.CRITICAL.value
    assert key_finding.confidence == "HIGH"
    assert key_finding.is_blocker is True

    # Invariant: Raw secret must NEVER appear in redacted_evidence, title, or description
    assert raw_key not in key_finding.redacted_evidence
    assert "-----BEGIN RSA PRIVATE KEY-----" not in key_finding.redacted_evidence
    assert "[REDACTED_RSA_KEY]" in key_finding.redacted_evidence


def test_aws_access_key_secret_detected_and_redacted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_aws_key = "AKIA1234567890ABCDEF"
    changed_files = [
        {"filename": "app/settings.py", "content": f'AWS_ACCESS_KEY = "{raw_aws_key}"\n', "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    assert assessment.security_status == SecurityStatus.SECURITY_BLOCKER.value
    aws_finding = next(f for f in assessment.findings if f.rule_id == "SEC_SECRET_AWS_KEY")
    assert aws_finding.severity == FindingSeverity.CRITICAL.value
    assert raw_aws_key not in aws_finding.redacted_evidence
    assert "AKIA" in aws_finding.redacted_evidence
    assert "REDACTED" in aws_finding.redacted_evidence


def test_github_token_detected_and_redacted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    raw_gh_token = "ghp_111122223333444455556666777788889999"
    changed_files = [
        {"filename": ".env.backup", "content": f"GITHUB_TOKEN={raw_gh_token}\n", "status": "added"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    gh_finding = next(f for f in assessment.findings if f.rule_id == "SEC_SECRET_GITHUB_TOKEN")
    assert gh_finding.severity == FindingSeverity.CRITICAL.value
    assert raw_gh_token not in gh_finding.redacted_evidence
    assert "ghp_" in gh_finding.redacted_evidence


def test_dummy_example_key_whitelisted(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    # "AKIAIOSFODNN7EXAMPLE" has EXAMPLE in it -> should be recognized as dummy test data
    changed_files = [
        {"filename": "tests/test_aws.py", "content": 'MOCK_KEY = "AKIAIOSFODNN7EXAMPLE"\n', "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    # Should not trigger SEC_SECRET_AWS_KEY because of whitelist
    assert not any(f.rule_id == "SEC_SECRET_AWS_KEY" for f in assessment.findings)


def test_tls_verification_disabled_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "client/api.py", "content": 'requests.get("https://internal.service", verify=False)\n', "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    tls_finding = next(f for f in assessment.findings if f.rule_id == "SEC_INSECURE_TLS_VERIFY_DISABLED")
    assert tls_finding.severity == FindingSeverity.HIGH.value
    assert tls_finding.category == SecurityCategory.INSECURE_CONFIGURATION.value
    assert tls_finding.confidence == "HIGH"


def test_sql_injection_concatenation_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "services/user_dao.py", "content": 'query = "SELECT * FROM users WHERE id = \'" + user_id + "\'"\ncursor.execute(query)\n', "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    sql_finding = next(f for f in assessment.findings if f.rule_id == "SEC_CODE_SQL_INJECTION")
    assert sql_finding.severity == FindingSeverity.HIGH.value
    assert sql_finding.category == SecurityCategory.INJECTION.value
    assert sql_finding.is_blocker is True


def test_command_injection_shell_true_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "runner.py", "content": 'subprocess.Popen(user_cmd, shell=True)\n', "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    cmd_finding = next(f for f in assessment.findings if f.rule_id == "SEC_CODE_COMMAND_INJECTION")
    assert cmd_finding.severity == FindingSeverity.CRITICAL.value
    assert cmd_finding.category == SecurityCategory.COMMAND_EXECUTION.value
    assert cmd_finding.is_blocker is True


def test_dependency_vulnerability_evidence_truthful_when_no_scanner(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    # Clean files
    changed_files = [
        {"filename": "models.py", "content": "class User:\n    pass\n", "status": "modified"}
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
        dependency_scanner_configured=False,
    )

    # Truthful reporting: evidence_status must be PARTIAL, not AVAILABLE
    assert assessment.evidence_status == EvidenceStatus.PARTIAL.value
    assert assessment.security_status == SecurityStatus.NO_SECURITY_FINDINGS.value
    assert assessment.security_risk_score == 0.0


def test_cross_repo_security_propagation(org_and_repo_id):
    org_id, source_repo_id = org_and_repo_id
    downstream_repo_id = uuid4()

    # Upstream introduces critical secret
    raw_key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----"
    changed_files = [
        {"filename": "auth/key.pem", "content": raw_key, "status": "added"}
    ]

    downstream_deps = [
        RepositoryDependency(
            organization_id=org_id,
            source_repository_id=downstream_repo_id,
            target_repository_id=source_repo_id,
            dependency_type=DependencyType.HTTP_API,
            confidence=DependencyConfidence.HIGH,
        )
    ]

    assessment = SecurityIntelligenceEngine.analyze_security(
        organization_id=org_id,
        repository_id=source_repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
        downstream_deps=downstream_deps,
    )

    prop_finding = next(
        (f for f in assessment.findings if f.rule_id == "SEC_DISTRIBUTED_DOWNSTREAM_EXPOSURE"),
        None
    )
    assert prop_finding is not None
    assert prop_finding.severity == FindingSeverity.HIGH.value
    assert prop_finding.is_blocker is True
    assert "Downstream consumer count: 1" in prop_finding.redacted_evidence
