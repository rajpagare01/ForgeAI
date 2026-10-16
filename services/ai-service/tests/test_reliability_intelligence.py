import pytest
from uuid import uuid4
from app.domain.models import (
    ReliabilityStatus,
    EvidenceStatus,
    ReliabilityCategory,
    FindingSeverity,
)
from app.application.reliability_intelligence_engine import ReliabilityIntelligenceEngine


@pytest.fixture
def org_and_repo_id():
    return uuid4(), uuid4()


def test_swallowed_exception_python_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "app/worker.py", "content": "try:\n    do_task()\nexcept Exception:\n    pass\n", "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    assert assessment.reliability_status == ReliabilityStatus.WARNING.value
    finding = next(f for f in assessment.findings if f.rule_id == "REL_SWALLOWED_EXCEPTION_PY")
    assert finding.severity == FindingSeverity.HIGH.value
    assert finding.category == ReliabilityCategory.ERROR_HANDLING.value
    assert finding.confidence == "HIGH"
    assert assessment.reliability_risk_score >= 20.0


def test_swallowed_exception_java_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "src/main/java/Service.java", "content": "try { execute(); } catch (Exception e) {}", "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_SWALLOWED_EXCEPTION_JAVA_JS")
    assert finding.severity == FindingSeverity.HIGH.value
    assert finding.category == ReliabilityCategory.ERROR_HANDLING.value


def test_missing_http_timeout_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "client.py", "content": 'resp = requests.post("https://api.external.com/data", json=payload)\n', "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_MISSING_HTTP_TIMEOUT_PY")
    assert finding.severity == FindingSeverity.HIGH.value
    assert finding.category == ReliabilityCategory.TIMEOUT.value
    assert "timeout" in finding.remediation.lower()


def test_unbounded_retry_loop_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    content = """
while True:
    try:
        connect()
        break
    except:
        continue
"""
    changed_files = [
        {"filename": "poller.py", "content": content, "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_UNBOUNDED_RETRY")
    assert finding.severity == FindingSeverity.MEDIUM.value
    assert finding.category == ReliabilityCategory.RETRY.value


def test_resource_leak_unclosed_file_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "exporter.py", "content": 'f = open("/tmp/output.txt", "w")\nf.write("data")\n', "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_RESOURCE_LEAK_OPEN")
    assert finding.severity == FindingSeverity.MEDIUM.value
    assert finding.category == ReliabilityCategory.RESOURCE_LEAK.value


def test_concurrency_unsafe_date_format_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "DateUtil.java", "content": 'public static SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");', "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_CONCURRENCY_UNSAFE_DATE_FORMAT")
    assert finding.severity == FindingSeverity.HIGH.value
    assert finding.category == ReliabilityCategory.CONCURRENCY.value


def test_historical_churn_hotspot_detected(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "core/engine.py", "content": "def run():\n    return True\n", "status": "modified"}
    ]
    historical_signals = [
        {"file_path": "core/engine.py", "churn_count": 14, "details": "14 prior commits in history"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
        historical_signals=historical_signals,
    )

    finding = next(f for f in assessment.findings if f.rule_id == "REL_HISTORICAL_CHURN_HOTSPOT")
    assert finding.severity == FindingSeverity.MEDIUM.value
    assert finding.category == ReliabilityCategory.HISTORICAL_INSTABILITY.value
    assert "14" in finding.evidence


def test_clean_reliability_returns_passed(org_and_repo_id):
    org_id, repo_id = org_and_repo_id
    changed_files = [
        {"filename": "safe.py", "content": 'with open("file.txt") as f:\n    content = f.read()\n', "status": "modified"}
    ]

    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        changed_files=changed_files,
        changed_symbols=[],
        retrieved_chunks=[],
    )

    assert assessment.reliability_status == ReliabilityStatus.PASSED.value
    assert assessment.reliability_risk_score == 0.0
    assert len(assessment.findings) == 0
