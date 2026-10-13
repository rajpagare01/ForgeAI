# ==============================================================================
# ForgeAI Production Staging Smoke Test Harness (PowerShell)
# ==============================================================================
param (
    [string]$ComposeFile = "docker-compose.prod.yml",
    [int]$TimeoutSecs = 300,
    [int]$PollInterval = 5,
    [string]$GatewayUrl = "http://localhost:8080"
)

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "          ForgeAI Production Staging Verification Suite           " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# 0. Pre-flight Docker Daemon check
Write-Host "`n[Pre-flight] Verifying Docker Engine connectivity..." -ForegroundColor Cyan
$null = docker version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Cannot connect to Docker Engine." -ForegroundColor Red
    Write-Host "Please start Docker Desktop and ensure the engine is running before starting the smoke test." -ForegroundColor Yellow
    exit 1
}

# 1. Spin up stack
Write-Host "`n[1/4] Building and launching containers via $ComposeFile..." -ForegroundColor Yellow
docker compose -f $ComposeFile up -d --build
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker Compose failed to build or start containers." -ForegroundColor Red
    exit 1
}

# 2. Container Health Polling Loop
Write-Host "`n[2/4] Waiting for all services to report 'healthy' (timeout: ${TimeoutSecs}s)..." -ForegroundColor Yellow

$ExpectedContainers = @(
    "forgeai-postgres-prod",
    "forgeai-redis-prod",
    "forgeai-identity-prod",
    "forgeai-project-prod",
    "forgeai-source-control-prod",
    "forgeai-testing-prod",
    "forgeai-ai-prod",
    "forgeai-api-gateway-prod",
    "forgeai-frontend-prod"
)

$sw = [System.Diagnostics.Stopwatch]::StartNew()

while ($true) {
    if ($sw.Elapsed.TotalSeconds -ge $TimeoutSecs) {
        Write-Host "`nTimed out after ${TimeoutSecs}s waiting for containers to become healthy!" -ForegroundColor Red
        docker compose -f $ComposeFile ps
        exit 1
    }

    $allHealthy = $true
    foreach ($container in $ExpectedContainers) {
        $status = docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container 2>$null
        if ($status -ne "healthy") {
            $allHealthy = $false
            break
        }
    }

    if ($allHealthy) {
        Write-Host "`nAll $($ExpectedContainers.Count) containers are HEALTHY in $([int]$sw.Elapsed.TotalSeconds)s!" -ForegroundColor Green
        break
    }

    Write-Host -NoNewline "."
    Start-Sleep -Seconds $PollInterval
}

# 3. End-to-End Health Probe Sweep via API Gateway (Port 8080) & Frontend (Port 3000)
Write-Host "`n`n[3/4] Performing Gateway & Downstream Health Probe Sweep..." -ForegroundColor Yellow

function Test-Endpoint {
    param([string]$Url, [string]$Name)
    try {
        $response = Invoke-WebRequest -Uri $Url -Method Get -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        Write-Host "$Name -> [PASS] HTTP $($response.StatusCode)" -ForegroundColor Green
    }
    catch {
        if ($_.Exception.Response) {
            $code = $_.Exception.Response.StatusCode.value__
            Write-Host "$Name -> [WARN] HTTP $code" -ForegroundColor Yellow
        } else {
            Write-Host "$Name -> [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

Test-Endpoint -Url "$GatewayUrl/actuator/health" -Name "API Gateway Health (/actuator/health)"
Test-Endpoint -Url "$GatewayUrl/.well-known/jwks.json" -Name "Identity JWKS Route (/.well-known/jwks.json)"
Test-Endpoint -Url "$GatewayUrl/api/v1/ai/health" -Name "AI Service Health (/api/v1/ai/health)"
Test-Endpoint -Url "http://localhost:3000/" -Name "Next.js Web Dashboard (http://localhost:3000/)"

# 4. Service Status & Summary Report
Write-Host "`n[4/4] Final Stack Health Summary:" -ForegroundColor Yellow
Write-Host "------------------------------------------------------------------"
docker compose -f $ComposeFile ps
Write-Host "------------------------------------------------------------------"

Write-Host "`nForgeAI Production Staging Verification Completed Successfully!" -ForegroundColor Green
