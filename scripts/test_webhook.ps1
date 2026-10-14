# Test GitHub Webhook Ingestion into ForgeAI via API Gateway (port 8080)
# Authoritative test script: resolves GITHUB_WEBHOOK_SECRET without logging it

param (
    [string]$GatewayUrl = "http://127.0.0.1:8080"
)

# 1. Prefer reading GITHUB_WEBHOOK_SECRET from environment variable
$secret = $env:GITHUB_WEBHOOK_SECRET

# 2. If not set in environment, read from .env file
if ([string]::IsNullOrWhiteSpace($secret)) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $envCandidates = @(
        (Join-Path $scriptDir "..\.env"),
        (Join-Path (Get-Location) ".env")
    )
    foreach ($envPath in $envCandidates) {
        if (Test-Path $envPath) {
            $line = Get-Content $envPath | Where-Object { $_ -match '^\s*GITHUB_WEBHOOK_SECRET\s*=\s*(.+)$' } | Select-Object -First 1
            if ($line) {
                $rawVal = ($line -replace '^\s*GITHUB_WEBHOOK_SECRET\s*=\s*', '').Trim('"', "'", " ")
                if (-not [string]::IsNullOrWhiteSpace($rawVal)) {
                    $secret = $rawVal
                    break
                }
            }
        }
    }
}

# 3. If still empty, check application.yml default
if ([string]::IsNullOrWhiteSpace($secret)) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $appYmlPath = Join-Path $scriptDir "..\services\source-control-service\src\main\resources\application.yml"
    if (Test-Path $appYmlPath) {
        $ymlLine = Get-Content $appYmlPath | Where-Object { $_ -match 'webhook-secret:\s*\$\{GITHUB_WEBHOOK_SECRET:(.+)\}' } | Select-Object -First 1
        if ($ymlLine) {
            $secret = ($ymlLine -replace '.*webhook-secret:\s*\$\{GITHUB_WEBHOOK_SECRET:(.+)\}.*', '$1').Trim('"', "'", " ")
        }
    }
}

# 4. Abort if secret is missing
if ([string]::IsNullOrWhiteSpace($secret)) {
    Write-Error "ERROR: GITHUB_WEBHOOK_SECRET is not configured in environment, .env, or application.yml. Aborting test."
    exit 1
}

# Note: The secret itself is NEVER logged or echoed.
Write-Host "Resolved GITHUB_WEBHOOK_SECRET: [CONFIGURED] (length: $($secret.Length))"

$payload = '{"action":"ping","zen":"Non-blocking is better than blocking."}'

# UTF8 without BOM
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$bytes = $utf8NoBom.GetBytes($payload)

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = $utf8NoBom.GetBytes($secret)
$hash = $hmac.ComputeHash($bytes)
$hex = [System.BitConverter]::ToString($hash).Replace("-","").ToLower()
$sig = "sha256=" + $hex
$deliveryId = [System.Guid]::NewGuid().ToString()

Write-Host "Generated Delivery ID: $deliveryId"
Write-Host "Computed HMAC Signature: $sig"

# Save payload to a temp file without BOM for curl.exe
$tempFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tempFile, $payload, $utf8NoBom)

try {
    Write-Host "Sending webhook request to $GatewayUrl/api/v1/webhooks/github ..."
    $curlOutput = & curl.exe -s -i `
        -X POST "$GatewayUrl/api/v1/webhooks/github" `
        -H "Content-Type: application/json" `
        -H "X-GitHub-Delivery: $deliveryId" `
        -H "X-GitHub-Event: ping" `
        -H "X-Hub-Signature-256: $sig" `
        --data-binary "@$tempFile"

    Write-Host "`nGateway / Source Control Response:"
    Write-Host ($curlOutput -join "`n")
} finally {
    if (Test-Path $tempFile) {
        Remove-Item -Force $tempFile
    }
}
