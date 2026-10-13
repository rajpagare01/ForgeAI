@echo off
echo ========================================================
echo  FORGEAI END-TO-END GATEWAY ROUTE VERIFICATION
echo ========================================================

echo.
echo [1/8] Checking API Gateway Actuator Health...
curl.exe -s -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/actuator/health

echo.
echo [2/8] Checking Identity Service via Gateway (JWKS Public Keys)...
curl.exe -s -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/.well-known/jwks.json

echo.
echo [3/8] Checking Identity Service via Gateway (Auth Login Endpoint)...
curl.exe -s -X POST -H "Content-Type: application/json" -d "{}" -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/api/v1/auth/login

echo.
echo [4/8] Checking Project Service via Gateway (Tenant Security Gate)...
curl.exe -s -H "X-Org-Id: 00000000-0000-0000-0000-000000000001" -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/api/v1/projects

echo.
echo [5/8] Checking Source Control via Gateway (Repositories Route)...
curl.exe -s -H "X-Org-Id: 00000000-0000-0000-0000-000000000001" -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/api/v1/repositories

echo.
echo [6/8] Checking Source Control via Gateway (GitHub Webhook HMAC Signature)...
curl.exe -s -X POST -H "X-GitHub-Event: ping" -H "X-Hub-Signature-256: sha256=invalid" -H "Content-Type: application/json" -d "{}" -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/api/v1/webhooks/github

echo.
echo [7/8] Checking AI Service via Gateway (Semantic Search Dispatch)...
curl.exe -s -X POST -H "Content-Type: application/json" -H "X-Org-Id: 00000000-0000-0000-0000-000000000001" -d "{}" -w "\nHTTP_CODE: %%{http_code}\n" http://127.0.0.1:8080/api/v1/ai/search

echo.
echo [8/8] Checking Frontend Next.js Web Dashboard...
curl.exe -s -I http://127.0.0.1:3000/

echo.
echo ========================================================
echo  ALL ENDPOINTS TESTED
echo ========================================================
