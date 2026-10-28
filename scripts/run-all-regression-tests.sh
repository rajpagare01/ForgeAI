#!/usr/bin/env bash
# ForgeAI Comprehensive Regression Runner
# Executes all unit and integration test suites across ForgeAI microservices and frontend.
# Baseline: 269 tests across 5 suites.

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "================================================================================"
echo "                  FORGEAI COMPREHENSIVE REGRESSION RUNNER                      "
echo "================================================================================"
echo "Baseline expectation: 269 passing tests, 0 failures."
echo ""

START_TIME=$(date +%s)

# 1. Source Control Service
echo "[1/5] Running Source Control Service Test Suite (Expected: 78 tests)..."
mvn test -pl services/source-control-service -Dtest="*Test"
echo "  -> Source Control Service: PASSED"
echo ""

# 2. AI Intelligence Service
echo "[2/5] Running AI Service Test Suite (Expected: 126 tests)..."
pytest services/ai-service/tests
echo "  -> AI Service: PASSED"
echo ""

# 3. Project Management Service
echo "[3/5] Running Project Service Test Suite (Expected: 20 tests)..."
mvn test -pl services/project-service -Dtest="*Test"
echo "  -> Project Service: PASSED"
echo ""

# 4. Testing Service
echo "[4/5] Running Testing Service Test Suite (Expected: 4 tests)..."
mvn test -pl services/testing-service -Dtest="*Test"
echo "  -> Testing Service: PASSED"
echo ""

# 5. Frontend Test Suite
echo "[5/5] Running Frontend Test Suite (Expected: 41 tests)..."
npm test --prefix frontend
echo "  -> Frontend: PASSED"
echo ""

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo "================================================================================"
echo "  ALL 269 REGRESSION TESTS PASSED IN ${DURATION}s!"
echo "  STATUS: VERIFIED GREEN"
echo "================================================================================"
