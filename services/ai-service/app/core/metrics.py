import threading
import time
from typing import Dict, Any, Optional

class AIMetricsCollector:
    def __init__(self):
        self._lock = threading.Lock()
        self.ai_analysis_total = 0
        self.ai_analysis_success_total = 0
        self.ai_analysis_failure_total = 0
        self.ai_provider_failures = 0
        self.ai_fallback_total = 0
        self.latencies = []  # recent latencies in ms

    def record_analysis(self, success: bool, latency_ms: float, fallback_used: bool = False, provider_failed: bool = False):
        with self._lock:
            self.ai_analysis_total += 1
            if success:
                self.ai_analysis_success_total += 1
            else:
                self.ai_analysis_failure_total += 1
            if fallback_used:
                self.ai_fallback_total += 1
            if provider_failed:
                self.ai_provider_failures += 1
            self.latencies.append(latency_ms)
            if len(self.latencies) > 1000:
                self.latencies.pop(0)

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            p50 = 0.0
            p95 = 0.0
            p99 = 0.0
            if self.latencies:
                sorted_lats = sorted(self.latencies)
                n = len(sorted_lats)
                p50 = sorted_lats[int(n * 0.50)]
                p95 = sorted_lats[min(int(n * 0.95), n - 1)]
                p99 = sorted_lats[min(int(n * 0.99), n - 1)]

            return {
                "ai_analysis_total": self.ai_analysis_total,
                "ai_analysis_success_total": self.ai_analysis_success_total,
                "ai_analysis_failure_total": self.ai_analysis_failure_total,
                "ai_provider_failures": self.ai_provider_failures,
                "ai_fallback_total": self.ai_fallback_total,
                "latency_p50_ms": p50,
                "latency_p95_ms": p95,
                "latency_p99_ms": p99,
            }

    def to_prometheus_format(self) -> str:
        stats = self.get_stats()
        lines = [
            "# HELP ai_analysis_total Total AI analysis requests",
            "# TYPE ai_analysis_total counter",
            f"ai_analysis_total {stats['ai_analysis_total']}",
            "# HELP ai_analysis_success_total Total successful AI analysis requests",
            "# TYPE ai_analysis_success_total counter",
            f"ai_analysis_success_total {stats['ai_analysis_success_total']}",
            "# HELP ai_analysis_failure_total Total failed AI analysis requests",
            "# TYPE ai_analysis_failure_total counter",
            f"ai_analysis_failure_total {stats['ai_analysis_failure_total']}",
            "# HELP ai_provider_failures Total AI provider failures",
            "# TYPE ai_provider_failures counter",
            f"ai_provider_failures {stats['ai_provider_failures']}",
            "# HELP ai_fallback_total Total AI fallback invocations",
            "# TYPE ai_fallback_total counter",
            f"ai_fallback_total {stats['ai_fallback_total']}",
            "# HELP ai_provider_latency_p95_ms Latency p95 in ms",
            "# TYPE ai_provider_latency_p95_ms gauge",
            f"ai_provider_latency_p95_ms {stats['latency_p95_ms']}",
        ]
        return "\n".join(lines) + "\n"

ai_metrics = AIMetricsCollector()
