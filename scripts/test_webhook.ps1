# Test GitHub Webhook Ingestion into ForgeAI via API Gateway (port 8080)
$secret = "dev-secret-key-12345"
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

Write-Host "Sending webhook request to API Gateway..."
$curlOutput = & curl.exe -s -i `
    -X POST "http://127.0.0.1:8080/api/v1/webhooks/github" `
    -H "Content-Type: application/json" `
    -H "X-GitHub-Delivery: $deliveryId" `
    -H "X-GitHub-Event: ping" `
    -H "X-Hub-Signature-256: $sig" `
    --data-binary "@$tempFile"

Remove-Item -Force $tempFile
Write-Host "Gateway / Source Control Response:"
Write-Host ($curlOutput -join "`n")
