param (
    [string]$GatewayUrl = "http://127.0.0.1:8080",
    [string]$Secret = $env:GITHUB_WEBHOOK_SECRET
)

$deliveryId = [System.Guid]::NewGuid().ToString()

$payload = @"
{
  "action": "opened",
  "number": 99,
  "pull_request": {
    "id": 990099,
    "number": 99,
    "state": "open",
    "title": "chore(test): harmless verification test PR #99",
    "body": "Harmless verification test commit and PR for ForgeAI Source Control webhook verification.",
    "head": {
      "ref": "feature/verify-webhook-test",
      "sha": "9999999999999999999999999999999999999999"
    },
    "base": {
      "ref": "main"
    },
    "created_at": "2026-10-17T07:50:00Z",
    "updated_at": "2026-10-17T07:50:00Z"
  },
  "repository": {
    "id": 1282456120,
    "name": "ForgeAI",
    "full_name": "rajpagare01/ForgeAI",
    "default_branch": "main",
    "private": true
  },
  "installation": {
    "id": 171260102
  }
}
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$bytes = $utf8NoBom.GetBytes($payload)

if (-not $Secret) {
    Write-Error "Secret is required. Pass -Secret or set GITHUB_WEBHOOK_SECRET environment variable."
    exit 1
}

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = $utf8NoBom.GetBytes($Secret)
$hash = $hmac.ComputeHash($bytes)
$hex = [System.BitConverter]::ToString($hash).Replace("-","").ToLower()
$sig = "sha256=" + $hex

$tempFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tempFile, $payload, $utf8NoBom)

try {
    Write-Host "Sending PR webhook: deliveryId=$deliveryId"
    $res = & curl.exe -s -i `
        -X POST "$GatewayUrl/api/v1/webhooks/github" `
        -H "Content-Type: application/json" `
        -H "X-GitHub-Delivery: $deliveryId" `
        -H "X-GitHub-Event: pull_request" `
        -H "X-Hub-Signature-256: $sig" `
        --data-binary "@$tempFile"

    Write-Host ($res -join "`n")
} finally {
    if (Test-Path $tempFile) {
        Remove-Item -Force $tempFile
    }
}
