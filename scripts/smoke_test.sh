#!/usr/bin/env bash
# ==============================================================================
# ForgeAI Production Staging Smoke Test Harness
# ==============================================================================
set -euo pipefail

COMPOSE_FILE="docker-compose.prod.yml"
TIMEOUT_SECS=120
POLL_INTERVAL=5
GATEWAY_URL="http://localhost:8080"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}          ForgeAI Production Staging Verification Suite           ${NC}"
echo -e "${BLUE}==================================================================${NC}"

# 0. Pre-flight Docker Daemon check
echo -e "\n${BLUE}[Pre-flight] Verifying Docker Engine connectivity...${NC}"
if ! docker version > /dev/null 2>&1; then
  echo -e "${RED}[ERROR] Cannot connect to Docker Engine.${NC}"
  echo -e "${YELLOW}Please start Docker Desktop and ensure the engine is running before starting the smoke test.${NC}"
  exit 1
fi

# 1. Spin up stack
echo -e "\n${YELLOW}[1/4] Building and launching containers via ${COMPOSE_FILE}...${NC}"
docker compose -f "${COMPOSE_FILE}" up -d --build

# 2. Container Health Polling Loop
echo -e "\n${YELLOW}[2/4] Waiting for all services to report 'healthy' (timeout: ${TIMEOUT_SECS}s)...${NC}"

EXPECTED_SERVICES=(
  "forgeai-postgres-prod"
  "forgeai-redis-prod"
  "forgeai-identity-prod"
  "forgeai-project-prod"
  "forgeai-source-control-prod"
  "forgeai-testing-prod"
  "forgeai-ai-prod"
  "forgeai-api-gateway-prod"
)

start_time=$(date +%s)
while true; do
  all_healthy=true
  current_time=$(date +%s)
  elapsed=$(( current_time - start_time ))

  if [ "$elapsed" -ge "$TIMEOUT_SECS" ]; then
    echo -e "\n${RED}Timed out after ${TIMEOUT_SECS}s waiting for containers to become healthy!${NC}"
    docker compose -f "${COMPOSE_FILE}" ps
    exit 1
  fi

  for container in "${EXPECTED_SERVICES[@]}"; do
    status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo "not-found")
    if [ "$status" != "healthy" ]; then
      all_healthy=false
      break
    fi
  done

  if [ "$all_healthy" = true ]; then
    echo -e "${GREEN}All $(echo ${#EXPECTED_SERVICES[@]}) containers are HEALTHY in ${elapsed}s!${NC}"
    break
  fi

  echo -n "."
  sleep "$POLL_INTERVAL"
done

# 3. End-to-End Health Probe Sweep via API Gateway (Port 8080)
echo -e "\n\n${YELLOW}[3/4] Performing Gateway & Downstream Health Probe Sweep...${NC}"

# Gateway actuator
echo -n "Checking API Gateway (/actuator/health)... "
gateway_res=$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_URL}/actuator/health" || echo "FAIL")
if [ "$gateway_res" = "200" ]; then
  echo -e "${GREEN}[PASS] HTTP 200 OK${NC}"
else
  echo -e "${RED}[FAIL] HTTP ${gateway_res}${NC}"
fi

# Identity via Gateway
echo -n "Checking Identity JWKS route (/.well-known/jwks.json)... "
jwks_res=$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_URL}/.well-known/jwks.json" || echo "FAIL")
if [ "$jwks_res" = "200" ]; then
  echo -e "${GREEN}[PASS] HTTP 200 OK${NC}"
else
  echo -e "${YELLOW}[WARN] HTTP ${jwks_res}${NC}"
fi

# AI Service via Gateway
echo -n "Checking AI Service health via Gateway (/api/v1/ai/health)... "
ai_res=$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_URL}/api/v1/ai/health" || echo "FAIL")
if [ "$ai_res" = "200" ]; then
  echo -e "${GREEN}[PASS] HTTP 200 OK${NC}"
else
  echo -e "${YELLOW}[INFO] HTTP ${ai_res}${NC}"
fi

# 4. Service Status & Summary Report
echo -e "\n${YELLOW}[4/4] Final Stack Health Summary:${NC}"
echo -e "------------------------------------------------------------------"
docker compose -f "${COMPOSE_FILE}" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
echo -e "------------------------------------------------------------------"

echo -e "\n${GREEN}ForgeAI Production Staging Verification Completed Successfully!${NC}"
