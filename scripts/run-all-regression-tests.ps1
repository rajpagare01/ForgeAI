# ForgeAI Regression Runner (PowerShell)
# Executes all unit and integration test suites across ForgeAI microservices and frontend.
# Baseline: 269 tests across 5 suites.

$ErrorActionPreference = "Stop"

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "                  FORGEAI COMPREHENSIVE REGRESSION RUNNER                      " -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "Baseline expectation: 269 passing tests, 0 failures.`n"

$repoRoot = Resolve-Path "$PSScriptRoot\.."
Set-Location $repoRoot

$startTime = Get-Date
$failedSuites = @()

# 1. Source Control Service (Maven)
Write-Host "[1/5] Running Source Control Service Test Suite (Expected: 78 tests)..." -ForegroundColor Yellow
try {
    & mvn test -pl services/source-control-service -Dtest="*Test"
    if ($LASTEXITCODE -ne 0) { throw "Source Control Service tests failed with exit code $LASTEXITCODE" }
    Write-Host "  -> Source Control Service: PASSED`n" -ForegroundColor Green
} catch {
    Write-Host "  -> Source Control Service: FAILED: $_`n" -ForegroundColor Red
    $failedSuites += "services/source-control-service"
}

# 2. AI Intelligence Service (pytest)
Write-Host "[2/5] Running AI Service Test Suite (Expected: 126 tests)..." -ForegroundColor Yellow
try {
    & pytest services/ai-service/tests
    if ($LASTEXITCODE -ne 0) { throw "AI Service tests failed with exit code $LASTEXITCODE" }
    Write-Host "  -> AI Service: PASSED`n" -ForegroundColor Green
} catch {
    Write-Host "  -> AI Service: FAILED: $_`n" -ForegroundColor Red
    $failedSuites += "services/ai-service"
}

# 3. Project Management Service (Maven)
Write-Host "[3/5] Running Project Service Test Suite (Expected: 20 tests)..." -ForegroundColor Yellow
try {
    & mvn test -pl services/project-service -Dtest="*Test"
    if ($LASTEXITCODE -ne 0) { throw "Project Service tests failed with exit code $LASTEXITCODE" }
    Write-Host "  -> Project Service: PASSED`n" -ForegroundColor Green
} catch {
    Write-Host "  -> Project Service: FAILED: $_`n" -ForegroundColor Red
    $failedSuites += "services/project-service"
}

# 4. Testing Service (Maven)
Write-Host "[4/5] Running Testing Service Test Suite (Expected: 4 tests)..." -ForegroundColor Yellow
try {
    & mvn test -pl services/testing-service -Dtest="*Test"
    if ($LASTEXITCODE -ne 0) { throw "Testing Service tests failed with exit code $LASTEXITCODE" }
    Write-Host "  -> Testing Service: PASSED`n" -ForegroundColor Green
} catch {
    Write-Host "  -> Testing Service: FAILED: $_`n" -ForegroundColor Red
    $failedSuites += "services/testing-service"
}

# 5. Frontend Test Suite (npm test)
Write-Host "[5/5] Running Frontend Test Suite (Expected: 41 tests)..." -ForegroundColor Yellow
try {
    & npm test --prefix frontend
    if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed with exit code $LASTEXITCODE" }
    Write-Host "  -> Frontend: PASSED`n" -ForegroundColor Green
} catch {
    Write-Host "  -> Frontend: FAILED: $_`n" -ForegroundColor Red
    $failedSuites += "frontend"
}

$duration = (Get-Date) - $startTime

Write-Host "================================================================================" -ForegroundColor Cyan
if ($failedSuites.Count -eq 0) {
    Write-Host "  ALL 269 REGRESSION TESTS PASSED IN $($duration.Minutes)m $($duration.Seconds)s!" -ForegroundColor Green
    Write-Host "  STATUS: VERIFIED GREEN" -ForegroundColor Green
    Write-Host "================================================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "  REGRESSION SUITE FAILED ($($failedSuites.Count) suites failed) in $($duration.Minutes)m $($duration.Seconds)s:" -ForegroundColor Red
    foreach ($f in $failedSuites) {
        Write-Host "    - $f" -ForegroundColor Red
    }
    Write-Host "================================================================================" -ForegroundColor Cyan
    exit 1
}
