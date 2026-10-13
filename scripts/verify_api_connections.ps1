# Comprehensive API and Microservice Gateway Verification Script
$ErrorActionPreference = "Continue"

$tests = @(
    @{ Name = "API Gateway Core Health"; Url = "http://127.0.0.1:8080/actuator/health"; Method = "GET"; Headers = @{} },
    @{ Name = "Redis Health via Gateway"; Url = "http://127.0.0.1:8080/actuator/health"; Method = "GET"; Headers = @{} },
    @{ Name = "Identity Service (JWKS Discovery)"; Url = "http://127.0.0.1:8080/.well-known/jwks.json"; Method = "GET"; Headers = @{} },
    @{ Name = "Identity Service (Auth Login Route)"; Url = "http://127.0.0.1:8080/api/v1/auth/login"; Method = "POST"; Headers = @{ "Content-Type" = "application/json" }; Body = "{}" },
    @{ Name = "Project Service (Tenant Validation)"; Url = "http://127.0.0.1:8080/api/v1/projects"; Method = "GET"; Headers = @{} },
    @{ Name = "Project Service (Scoped with Tenant)"; Url = "http://127.0.0.1:8080/api/v1/projects"; Method = "GET"; Headers = @{ "X-Org-Id" = "00000000-0000-0000-0000-000000000001" } },
    @{ Name = "Source Control (Scoped Repositories)"; Url = "http://127.0.0.1:8080/api/v1/repositories"; Method = "GET"; Headers = @{ "X-Org-Id" = "00000000-0000-0000-0000-000000000001" } },
    @{ Name = "Source Control (GitHub Webhook Security)"; Url = "http://127.0.0.1:8080/api/v1/webhooks/github"; Method = "POST"; Headers = @{ "X-GitHub-Event" = "ping"; "X-Hub-Signature-256" = "sha256=invalid" }; Body = "{}" },
    @{ Name = "Testing Service (Test Runs Dispatch)"; Url = "http://127.0.0.1:8080/api/v1/test-runs"; Method = "POST"; Headers = @{ "Content-Type" = "application/json" }; Body = "{}" },
    @{ Name = "AI Service (Missing Tenant Protection)"; Url = "http://127.0.0.1:8080/api/v1/ai/search"; Method = "POST"; Headers = @{ "Content-Type" = "application/json" }; Body = "{}" },
    @{ Name = "AI Service (Scoped Tenant Validation)"; Url = "http://127.0.0.1:8080/api/v1/ai/search"; Method = "POST"; Headers = @{ "Content-Type" = "application/json"; "X-Org-Id" = "00000000-0000-0000-0000-000000000001" }; Body = "{}" },
    @{ Name = "Frontend Next.js UI App"; Url = "http://127.0.0.1:3000/"; Method = "GET"; Headers = @{} }
)

$results = foreach ($item in $tests) {
    try {
        $req = [System.Net.HttpWebRequest]::Create($item.Url)
        $req.Method = $item.Method
        $req.Timeout = 15000
        
        foreach ($k in $item.Headers.Keys) {
            if ($k -eq "Content-Type") {
                $req.ContentType = $item.Headers[$k]
            } else {
                $req.Headers.Add($k, $item.Headers[$k])
            }
        }

        if ($item.Body) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($item.Body)
            $req.ContentLength = $bytes.Length
            $reqStream = $req.GetRequestStream()
            $reqStream.Write($bytes, 0, $bytes.Length)
            $reqStream.Close()
        }

        $resp = $req.GetResponse()
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $content = $reader.ReadToEnd()
        $statusCode = [int]$resp.StatusCode

        [PSCustomObject]@{
            Endpoint = $item.Name
            Url = $item.Url
            Status = "$statusCode OK"
            Connectivity = "CONNECTED"
            ResponseSummary = if ($content.Length -gt 60) { $content.Substring(0, 60).Replace("`n","").Replace("`r","") + "..." } else { $content.Replace("`n","").Replace("`r","") }
        }
    } catch [System.Net.WebException] {
        $webEx = $_.Exception
        if ($webEx.Response) {
            $statusCode = [int]$webEx.Response.StatusCode
            $reader = New-Object System.IO.StreamReader($webEx.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            
            [PSCustomObject]@{
                Endpoint = $item.Name
                Url = $item.Url
                Status = "$statusCode $($webEx.Response.StatusCode)"
                Connectivity = "CONNECTED"
                ResponseSummary = if ($content.Length -gt 60) { $content.Substring(0, 60).Replace("`n","").Replace("`r","") + "..." } else { $content.Replace("`n","").Replace("`r","") }
            }
        } else {
            [PSCustomObject]@{
                Endpoint = $item.Name
                Url = $item.Url
                Status = "FAILED"
                Connectivity = "DISCONNECTED"
                ResponseSummary = $webEx.Message
            }
        }
    }
}

$results | Format-Table -AutoSize
