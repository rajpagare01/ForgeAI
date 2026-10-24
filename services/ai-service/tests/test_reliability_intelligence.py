import pytest
from uuid import uuid4
from app.application.reliability_intelligence_engine import ReliabilityIntelligenceEngine
from app.domain.models import ReliabilityStatus, FindingSeverity


def test_detect_unsafe_optional_deref():
    code = """
    public String getUserName(Optional<User> userOpt) {
        return userOpt.get().getName();
    }
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "UserService.java")
    opt_findings = [f for f in findings if f.rule_id == "REL_UNSAFE_OPTIONAL_DEREF"]
    assert len(opt_findings) >= 1
    assert opt_findings[0].metadata.get("detection_status") == "LIKELY"
    assert opt_findings[0].severity == FindingSeverity.HIGH.value


def test_detect_swallowed_promise_rejection():
    code = """
    async function loadData() {
        api.fetchUser().catch(() => {});
    }
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "api.ts")
    p_findings = [f for f in findings if f.rule_id == "REL_SWALLOWED_PROMISE_REJECTION"]
    assert len(p_findings) >= 1
    assert p_findings[0].metadata.get("detection_status") == "DETECTED"


def test_detect_generic_exception_throw():
    code = """
    def validate_age(age):
        if age < 0:
            raise Exception("Invalid age")
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "validator.py")
    e_findings = [f for f in findings if f.rule_id == "REL_GENERIC_EXCEPTION_THROW"]
    assert len(e_findings) >= 1
    assert e_findings[0].metadata.get("detection_status") == "REQUIRES REVIEW"


def test_detect_missing_http_timeout_java():
    code = """
    public void callUpstream() {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();
    }
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "Client.java")
    t_findings = [f for f in findings if f.rule_id == "REL_MISSING_HTTP_TIMEOUT_JAVA"]
    assert len(t_findings) >= 1
    assert t_findings[0].metadata.get("detection_status") == "DETECTED"


def test_detect_dangerous_input_deserialization():
    code = """
    import pickle
    data = pickle.loads(raw_input)
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "loader.py")
    d_findings = [f for f in findings if f.rule_id == "REL_DANGEROUS_INPUT_DESERIALIZATION"]
    assert len(d_findings) >= 1
    assert d_findings[0].is_blocker is True


def test_detect_unclosed_resource_jdbc():
    code = """
    public void executeQuery(Connection conn) throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    }
    """
    findings = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(code, "Dao.java")
    r_findings = [f for f in findings if f.rule_id == "REL_UNCLOSED_RESOURCE_JDBC"]
    assert len(r_findings) >= 1
    assert r_findings[0].metadata.get("detection_status") == "SUSPICIOUS"


def test_analyze_reliability_assessment_aggregation():
    org_id = uuid4()
    repo_id = uuid4()
    files = [
        {
            "filename": "services/OrderService.java",
            "content": """
            public void process() {
                Statement stmt = conn.createStatement();
                Optional<Order> opt = findOrder();
                opt.get().fulfill();
            }
            """,
        }
    ]
    assessment = ReliabilityIntelligenceEngine.analyze_reliability(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=10,
        commit_sha="abcdef123456",
        changed_files=files,
    )
    assert assessment.high_count >= 1
    assert assessment.reliability_status == ReliabilityStatus.WARNING.value
    assert assessment.reliability_risk_score > 0
