import os
import sys
import uuid
import time
import requests
from typing import Dict, Any

class CrossTenantSecurityValidator:
    """
    Requirement 39: Cross-Tenant Security Audit
    Organization A creates proprietary data (projects, tasks, repositories, test runs, code chunks).
    Organization B attempts to read or mutate Organization A data.
    All unauthorized cross-tenant operations MUST fail closed.
    """
    def __init__(self, gateway_url: str = os.getenv("GATEWAY_URL", "http://127.0.0.1:8080")):
        self.gateway_url = gateway_url.rstrip("/")
        self.session = requests.Session()
        
        # Tenant A
        ts_a = int(time.time())
        self.user_a_email = f"alice_{ts_a}@tenant-a.com"
        self.user_a_name = f"alice_{ts_a}"
        self.token_a = None
        self.org_a_id = None
        self.project_a_id = None
        self.task_a_id = None
        self.repo_a_id = None
        self.test_run_a_id = None

        # Tenant B
        ts_b = ts_a + 1
        self.user_b_email = f"bob_{ts_b}@tenant-b.com"
        self.user_b_name = f"bob_{ts_b}"
        self.token_b = None
        self.org_b_id = None

    def log(self, msg: str):
        print(f"  [SECURITY] {msg}")

    def auth_headers(self, token: str, org_id: str) -> Dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "X-Org-Id": org_id,
            "X-Correlation-Id": str(uuid.uuid4()),
        }

    def authenticate_user(self, email: str, username: str, password: str = "P@ssw0rd123!") -> str:
        # 1. Register
        reg_url = f"{self.gateway_url}/api/v1/auth/register"
        self.session.post(reg_url, json={
            "email": email,
            "username": username,
            "password": password,
            "firstName": "Security",
            "lastName": "Tester",
        }, timeout=25)

        # 2. Login
        login_url = f"{self.gateway_url}/api/v1/auth/login"
        res = self.session.post(login_url, json={
            "identifier": email,
            "password": password,
        }, timeout=25)
        if res.status_code != 200:
            raise RuntimeError(f"Authentication failed for {email}: {res.text}")
        return res.json()["accessToken"]

    def create_org(self, token: str, name: str, slug: str) -> str:
        res = self.session.post(
            f"{self.gateway_url}/api/v1/organizations",
            json={"name": name, "slug": slug, "description": "Tenant isolation test org"},
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            timeout=25,
        )
        if res.status_code != 201:
            raise RuntimeError(f"Failed to create org {slug}: {res.text}")
        return str(res.json()["id"])

    def run_audit(self):
        print("\n" + "=" * 80)
        print("RUNNING AUTOMATED CROSS-TENANT SECURITY ISOLATION SUITE")
        print("=" * 80)

        # Step 1: Provision Tenant A
        self.log("Provisioning Tenant A (Organization A)...")
        self.token_a = self.authenticate_user(self.user_a_email, self.user_a_name)
        self.org_a_id = self.create_org(self.token_a, "Alpha Defense Systems", f"alpha-{int(time.time())}")
        self.log(f"Tenant A Org ID: {self.org_a_id}")

        # Create Project A
        res = self.session.post(
            f"{self.gateway_url}/api/v1/projects",
            json={"name": "Project Alpha Confidential", "key": "ALPH", "description": "Top Secret"},
            headers=self.auth_headers(self.token_a, self.org_a_id),
            timeout=25,
        )
        assert res.status_code == 201, f"Failed to create Project A: {res.text}"
        self.project_a_id = str(res.json()["id"])
        self.log(f"Created Project A ID: {self.project_a_id}")

        # Connect Repo A
        repo_github_id = int(time.time()) % 10000000 + 200000
        res = self.session.post(
            f"{self.gateway_url}/api/v1/repositories/connect",
            json={"githubRepoId": repo_github_id, "name": f"alpha-core-{repo_github_id}", "fullName": f"alpha/core-{repo_github_id}", "defaultBranch": "main", "isPrivate": True},
            headers=self.auth_headers(self.token_a, self.org_a_id),
            timeout=25,
        )
        assert res.status_code == 201, f"Failed to connect Repo A: {res.text}"
        self.repo_a_id = str(res.json()["id"])
        self.log(f"Connected Repo A ID: {self.repo_a_id}")

        # Create Test Run A
        res = self.session.post(
            f"{self.gateway_url}/api/v1/test-runs",
            json={"repositoryId": self.repo_a_id, "commitSha": "deadbeef1111", "branch": "main"},
            headers=self.auth_headers(self.token_a, self.org_a_id),
            timeout=25,
        )
        assert res.status_code == 201, f"Failed to create Test Run A: {res.text}"
        self.test_run_a_id = str(res.json()["id"])
        self.log(f"Created Test Run A ID: {self.test_run_a_id}")

        # Step 2: Provision Tenant B
        self.log("\nProvisioning Tenant B (Organization B)...")
        self.token_b = self.authenticate_user(self.user_b_email, self.user_b_name)
        self.org_b_id = self.create_org(self.token_b, "Beta Commercial Systems", f"beta-{int(time.time())}")
        self.log(f"Tenant B Org ID: {self.org_b_id}")

        # Step 3: Execute Cross-Tenant Infiltration Tests
        self.log("\n--- EXECUTING CROSS-TENANT ADVERSARIAL CHECKS ---")

        # Attack 1: User B directly queries Project A by ID
        self.log("Check 1: User B attempts direct GET /api/v1/projects/{project_a_id}")
        res = self.session.get(
            f"{self.gateway_url}/api/v1/projects/{self.project_a_id}",
            headers=self.auth_headers(self.token_b, self.org_b_id),
            timeout=25,
        )
        assert res.status_code in (403, 404), f"LEAK! User B retrieved Project A: HTTP {res.status_code}"
        self.log("  [PASS] Direct Project A query rejected with HTTP " + str(res.status_code))

        # Attack 2: User B lists projects under Org B
        self.log("Check 2: User B lists projects; ensure Project A is NOT visible")
        res = self.session.get(
            f"{self.gateway_url}/api/v1/projects",
            headers=self.auth_headers(self.token_b, self.org_b_id),
            timeout=25,
        )
        assert res.status_code == 200
        proj_list = res.json()
        found_a = any(p["id"] == self.project_a_id for p in proj_list)
        assert not found_a, "LEAK! Project A appeared in User B's project listing!"
        self.log("  [PASS] Project A excluded from Tenant B query results")

        # Attack 3: User B attempts direct GET /api/v1/repositories/{repo_a_id}
        self.log("Check 3: User B attempts direct GET /api/v1/repositories/{repo_a_id}")
        res = self.session.get(
            f"{self.gateway_url}/api/v1/repositories/{self.repo_a_id}",
            headers=self.auth_headers(self.token_b, self.org_b_id),
            timeout=25,
        )
        assert res.status_code in (403, 404), f"LEAK! User B retrieved Repo A: HTTP {res.status_code}"
        self.log("  [PASS] Direct Repo A query rejected with HTTP " + str(res.status_code))

        # Attack 4: User B lists repositories; ensure Repo A is NOT visible
        self.log("Check 4: User B lists repositories; ensure Repo A is NOT visible")
        res = self.session.get(
            f"{self.gateway_url}/api/v1/repositories",
            headers=self.auth_headers(self.token_b, self.org_b_id),
            timeout=25,
        )
        assert res.status_code == 200
        repo_list = res.json()
        found_repo = any(r["id"] == self.repo_a_id for r in repo_list)
        assert not found_repo, "LEAK! Repo A appeared in User B's repository listing!"
        self.log("  [PASS] Repo A excluded from Tenant B query results")

        # Attack 5: User B attempts direct GET /api/v1/test-runs/{test_run_a_id}
        self.log("Check 5: User B attempts direct GET /api/v1/test-runs/{test_run_a_id}")
        res = self.session.get(
            f"{self.gateway_url}/api/v1/test-runs/{self.test_run_a_id}",
            headers=self.auth_headers(self.token_b, self.org_b_id),
            timeout=25,
        )
        assert res.status_code in (403, 404), f"LEAK! User B retrieved Test Run A: HTTP {res.status_code}"
        self.log("  [PASS] Direct Test Run A query rejected with HTTP " + str(res.status_code))

        # Attack 6: User B attempts spoofed X-Org-Id header targeting Org A
        self.log("Check 6: Zero-Trust Perimeter - Spoofed X-Org-Id header check")
        spoofed_headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token_b}",
            "X-Org-Id": self.org_a_id,  # Adversary injects Org A UUID while using Token B
        }
        res = self.session.get(
            f"{self.gateway_url}/api/v1/projects/{self.project_a_id}",
            headers=spoofed_headers,
            timeout=25,
        )
        assert res.status_code in (403, 404), f"CRITICAL LEAK! Gateway trusted spoofed X-Org-Id header: HTTP {res.status_code}"
        self.log("  [PASS] Gateway sanitized spoofed X-Org-Id; access denied with HTTP " + str(res.status_code))

        print("\n" + "*" * 80)
        print("ALL 6 CROSS-TENANT ISOLATION CHECKS PASSED: ZERO LEAKAGE DETECTED!")
        print("*" * 80)

if __name__ == "__main__":
    validator = CrossTenantSecurityValidator()
    try:
        validator.run_audit()
        sys.exit(0)
    except Exception as e:
        print(f"\n[FAIL] CROSS-TENANT SECURITY AUDIT FAILED: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
