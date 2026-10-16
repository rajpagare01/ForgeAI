import logging
from typing import Dict, Any, Optional

from app.domain.models import (
    UnifiedRiskScore,
    FindingSeverity,
    SecurityAssessment,
    ReliabilityAssessment,
)

logger = logging.getLogger(__name__)


class UnifiedRiskEngine:
    """
    Unified Engineering Risk Aggregator.
    Deterministically aggregates:
      1. Local Risk (0–100) from DeterministicRiskEngine
      2. Distributed Risk (0–50) from DistributedRiskEngine
      3. Security Risk (0–100) from SecurityIntelligenceEngine
      4. Reliability Risk (0–100) from ReliabilityIntelligenceEngine

    ==========================================================================
    EXPLICIT FORMULA:
    base_risk = (0.35 * local_risk) + (0.15 * distributed_risk) + (0.35 * security_risk) + (0.15 * reliability_risk)

    Critical Elevation Floor:
      If any CRITICAL security blocker exists:
        final_risk = max(base_risk, 75.0)  --> elevates directly to CRITICAL tier.
      Else:
        final_risk = min(100.0, base_risk)

    Risk Level Boundaries:
      0.0  to 25.0  --> LOW
      25.1 to 50.0  --> MEDIUM
      50.1 to 75.0  --> HIGH
      75.1 to 100.0 --> CRITICAL
    ==========================================================================
    """

    @classmethod
    def calculate_unified_risk(
        cls,
        local_risk: float,
        distributed_risk: float,
        security_assessment: Optional[SecurityAssessment] = None,
        reliability_assessment: Optional[ReliabilityAssessment] = None,
    ) -> UnifiedRiskScore:
        l_risk = max(0.0, min(100.0, float(local_risk)))
        d_risk = max(0.0, min(50.0, float(distributed_risk)))
        s_risk = max(0.0, min(100.0, float(security_assessment.security_risk_score if security_assessment else 0.0)))
        r_risk = max(0.0, min(100.0, float(reliability_assessment.reliability_risk_score if reliability_assessment else 0.0)))

        # Weighted calculation
        c_local = 0.35 * l_risk
        c_dist = 0.15 * d_risk
        c_sec = 0.35 * s_risk
        c_rel = 0.15 * r_risk
        base_risk = c_local + c_dist + c_sec + c_rel

        # Check for critical security elevation
        has_critical_security = False
        if security_assessment and security_assessment.findings:
            has_critical_security = any(
                f.severity == FindingSeverity.CRITICAL.value and f.confidence != "LOW"
                for f in security_assessment.findings
            )

        if has_critical_security:
            final_risk = max(base_risk, 75.0)
        else:
            final_risk = min(100.0, base_risk)

        final_risk = round(final_risk, 1)

        # Categorical level
        if final_risk >= 75.0:
            level = "CRITICAL"
        elif final_risk > 50.0:
            level = "HIGH"
        elif final_risk > 25.0:
            level = "MEDIUM"
        else:
            level = "LOW"

        return UnifiedRiskScore(
            local_risk=round(l_risk, 1),
            distributed_risk=round(d_risk, 1),
            security_risk=round(s_risk, 1),
            reliability_risk=round(r_risk, 1),
            final_risk=final_risk,
            final_risk_level=level,
            risk_formula="min(100.0, 0.35*local + 0.15*distributed + 0.35*security + 0.15*reliability) with critical security floor",
            dimension_contributions={
                "local": round(c_local, 1),
                "distributed": round(c_dist, 1),
                "security": round(c_sec, 1),
                "reliability": round(c_rel, 1),
                "local_contribution": round(c_local, 1),
                "distributed_contribution": round(c_dist, 1),
                "security_contribution": round(c_sec, 1),
                "reliability_contribution": round(c_rel, 1),
            },
        )
