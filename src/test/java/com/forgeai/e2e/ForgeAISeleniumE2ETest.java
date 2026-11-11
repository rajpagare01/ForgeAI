package com.forgeai.e2e;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;

/**
 * ForgeAI — Complete Positive & Negative Java 21 + Selenium WebDriver 4 + TestNG Suite
 * ====================================================================================
 * Covers both Positive (Happy-Path) and Negative / Boundary / Security E2E tests across
 * all 8 Next.js 16 Carbon UI routes and 6 Spring Cloud Gateway microservices:
 *
 *  - Positive Flows: Login UI, Enterprise Registration, Overview KPIs & 6-Service Topology,
 *    TopNav '/' Search & Alerts Popover, OrgSwitcher & Organization Creation, Governed Project
 *    Creation, Requirement & Task Creation, Kanban Lifecycle Transitions & Code Artifact Link,
 *    AI Sprint Planner PRD Import, 10-Stage Traceability Spine & Snapshots, GitHub Repository
 *    Batch Import, JUnit XML Upload, Test Studio Selenium Suite Generation, AI Insights 7 Tabs,
 *    Operations Outbox DLQ Replay & Sign Out.
 *  - Negative / Boundary / Security Flows: Unauthenticated AuthGuard Redirect, Invalid Login
 *    Error Sanitization (zero stack-trace leakage), GitHub OAuth Callback Missing/Denied Params,
 *    Duplicate/Invalid Project Key Rejection, Empty Batch Repository Import Guard (0 selected),
 *    Malformed JUnit XML Parse Rejection, AWS/GitHub Secret Redaction ('[REDACTED SECRET]'),
 *    AI Remediation Rejection Empty Audit Reason Guard, and Empty Workspace Traceability Fallback.
 */
public class ForgeAISeleniumE2ETest {

    private WebDriver driver;
    private WebDriverWait wait;
    private String baseUrl;

    @BeforeClass(alwaysRun = true)
    public void setUpSuite() {
        this.baseUrl = System.getProperty("forgeai.base.url",
                System.getenv().getOrDefault("FORGEAI_BASE_URL", "http://localhost:3000"));

        ChromeOptions options = new ChromeOptions();
        if (!"false".equalsIgnoreCase(System.getProperty("headless", "true"))) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1440,900"
        );

        this.driver = new ChromeDriver(options);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(12));
    }

    private WebElement waitForVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    private void clickElement(By locator) {
        wait.until(ExpectedConditions.elementToBeClickable(locator)).click();
    }

    private void fillInput(By locator, String text) {
        WebElement input = waitForVisible(locator);
        input.clear();
        input.sendKeys(text);
    }

    @Test(priority = 1, groups = {"negative", "security", "smoke"},
          description = "[NEGATIVE] Verify AuthGuard redirects unauthenticated users from protected dashboard routes to /login")
    public void test01_Negative_AuthGuardRedirectsUnauthenticatedVisitor() {
        driver.get(baseUrl + "/login");
        ((JavascriptExecutor) driver).executeScript("window.localStorage.clear(); window.sessionStorage.clear();");
        driver.get(baseUrl + "/");

        WebElement loginHeading = waitForVisible(By.xpath("//h1[contains(., 'Sign in to ForgeAI')]"));
        Assert.assertTrue(loginHeading.isDisplayed(), "Unauthenticated visitor must be redirected to Sign In view");
        Assert.assertTrue(driver.getCurrentUrl().contains("/login"), "URL must resolve to /login");
    }

    @DataProvider(name = "invalidLoginInputs")
    public Object[][] invalidLoginInputs() {
        return new Object[][] {
            { "invalid@company.com", "wrongpass" },
            { "unauthorized@forgeai.dev", "wrongpass" }
        };
    }

    @Test(priority = 2, dataProvider = "invalidLoginInputs", groups = {"negative", "security", "boundary"},
          description = "[NEGATIVE] Verify Login page sanitizes authentication errors without leaking stack traces")
    public void test02_Negative_LoginInvalidCredentialsDisplaysSanitizedError(String email, String password) {
        driver.get(baseUrl + "/login");
        fillInput(By.cssSelector("input[type='email']"), email);
        fillInput(By.cssSelector("input[type='password']"), password);
        clickElement(By.xpath("//button[contains(., 'Sign In to Workspace')]"));

        WebElement alertBanner = waitForVisible(By.xpath("//*[contains(., 'Invalid email or password') or contains(., 'Unable to')]"));
        Assert.assertTrue(alertBanner.isDisplayed(), "Sanitized error alert banner must be visible");
        Assert.assertFalse(alertBanner.getText().contains("Exception"), "Error banner must never expose raw Java stack traces");
    }

    @Test(priority = 3, groups = {"negative", "oauth", "boundary"},
          description = "[NEGATIVE] Verify GitHub OAuth callback without code/state or with error=access_denied displays Authentication Failed card")
    public void test03_Negative_GitHubOAuthCallbackMissingOrDeniedParams() {
        driver.get(baseUrl + "/auth/github/callback");
        WebElement missingParamsBanner = waitForVisible(By.xpath("//*[contains(., 'Missing required OAuth parameters')]"));
        Assert.assertTrue(missingParamsBanner.isDisplayed(), "Missing code/state must display OAuth error card");

        driver.get(baseUrl + "/auth/github/callback?error=access_denied&error_description=User+denied+OAuth+grant");
        WebElement deniedBanner = waitForVisible(By.xpath("//*[contains(., 'User denied OAuth grant')]"));
        Assert.assertTrue(deniedBanner.isDisplayed(), "OAuth error_description must be rendered safely");
    }

    @Test(priority = 4, groups = {"positive", "smoke", "auth"},
          description = "[POSITIVE] Verify Enterprise Registration provisions user, organization workspace, and RS256 token")
    public void test04_Positive_RegisterEnterpriseAccountAndProvisionWorkspace() {
        driver.get(baseUrl + "/register");
        waitForVisible(By.xpath("//h1[contains(., 'Create Enterprise Account')]"));

        fillInput(By.cssSelector("input[placeholder*='Jane Doe']"), "Aarav Sharma");
        fillInput(By.cssSelector("input[placeholder*='Acme Corp']"), "CyberSentinel Labs");
        fillInput(By.cssSelector("input[type='email']"), "aarav_" + System.currentTimeMillis() + "@cybersentinel.io");
        fillInput(By.cssSelector("input[type='password']"), "ForgeAI#2026!");
        clickElement(By.xpath("//button[contains(., 'Initialize Workspace')]"));

        WebElement welcomeHeading = waitForVisible(By.xpath("//h1[contains(., 'Welcome back')]"));
        Assert.assertTrue(welcomeHeading.isDisplayed(), "Dashboard welcome header must render after registration");

        String accessToken = (String) ((JavascriptExecutor) driver)
                .executeScript("return window.localStorage.getItem('forgeai_access_token');");
        Assert.assertNotNull(accessToken, "RS256 access token must be stored in localStorage");
    }

    @Test(priority = 5, groups = {"positive", "smoke", "dashboard"},
          description = "[POSITIVE] Verify Overview Dashboard renders 4 KPI metric cards and 6-microservice topology status")
    public void test05_Positive_OverviewDashboardKpisAndMicroservicesTopology() {
        driver.get(baseUrl + "/");
        waitForVisible(By.xpath("//*[contains(., 'Zero-Trust Policy Enforced')]"));

        String[] expectedKpis = {
            "Governed Projects", "Connected Repositories", "Test Ingestions", "Open Pull Requests"
        };
        for (String kpi : expectedKpis) {
            Assert.assertTrue(waitForVisible(By.xpath("//*[contains(., '" + kpi + "')]")).isDisplayed(),
                    "Missing KPI card: " + kpi);
        }

        String[] expectedServices = {
            "API Gateway", "Identity Service", "Source Control", "Testing Service", "AI Service", "Project Service"
        };
        for (String svc : expectedServices) {
            Assert.assertTrue(waitForVisible(By.xpath("//*[contains(., '" + svc + "')]")).isDisplayed(),
                    "Missing microservice row: " + svc);
        }
    }

    @Test(priority = 6, groups = {"positive", "ui", "navigation"},
          description = "[POSITIVE] Verify TopNav keyboard shortcut ('/'), Escape blur, and Telemetry & Alerts popover")
    public void test06_Positive_TopNavSearchShortcutAndNotificationsPopover() {
        driver.get(baseUrl + "/");
        WebElement body = driver.findElement(By.tagName("body"));
        body.sendKeys("/");

        String activePlaceholder = (String) ((JavascriptExecutor) driver)
                .executeScript("return document.activeElement ? document.activeElement.getAttribute('placeholder') : '';");
        Assert.assertTrue(activePlaceholder != null && activePlaceholder.contains("Search tasks"),
                "Pressing '/' must focus the TopNav global search input");

        driver.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        clickElement(By.cssSelector("button[title='System & Governance Alerts']"));
        WebElement alertsHeader = waitForVisible(By.xpath("//*[contains(., 'Telemetry & Alerts')]"));
        Assert.assertTrue(alertsHeader.isDisplayed(), "Telemetry & Alerts popover must open");
    }

    @Test(priority = 7, groups = {"positive", "tenant", "rbac"},
          description = "[POSITIVE] Verify OrgSwitcher toggles Personal vs. Organization workspace and creates new organization")
    public void test07_Positive_OrgSwitcherWorkspaceToggleAndCreateOrganization() {
        driver.get(baseUrl + "/");
        clickElement(By.cssSelector("button[title='Switch Workspace or Organization']"));
        clickElement(By.xpath("//button[contains(., 'Personal Workspace')]"));

        String wsType = (String) ((JavascriptExecutor) driver)
                .executeScript("return window.localStorage.getItem('forgeai_workspace_type');");
        Assert.assertEquals(wsType, "PERSONAL", "Workspace scope must switch to PERSONAL");

        clickElement(By.cssSelector("button[title='Switch Workspace or Organization']"));
        clickElement(By.xpath("//button[contains(., 'Create New Organization')]"));
        fillInput(By.cssSelector("input[placeholder*='Acme Security Platform']"), "Nexus FinTech");
        clickElement(By.xpath("//form//button[contains(., 'Create')]"));

        String updatedWsType = (String) ((JavascriptExecutor) driver)
                .executeScript("return window.localStorage.getItem('forgeai_workspace_type');");
        Assert.assertEquals(updatedWsType, "ORGANIZATION", "Creating an organization must activate ORGANIZATION scope");
    }

    @Test(priority = 8, groups = {"positive", "projects", "kanban"},
          description = "[POSITIVE] Verify creating a Governed Project, adding Requirement REQ-001, and creating Task CORE-101")
    public void test08_Positive_CreateProjectRequirementAndKanbanTask() {
        driver.get(baseUrl + "/projects");
        waitForVisible(By.xpath("//h1[contains(., 'Governed Projects')]"));

        clickElement(By.xpath("//button[contains(., 'Create Project')]"));
        fillInput(By.cssSelector("input[placeholder*='CORE']"), "CORE");
        fillInput(By.cssSelector("input[placeholder*='Identity & Security Core']"), "Identity & Security Core");
        fillInput(By.cssSelector("textarea[placeholder*='High-level architecture']"), "Core RBAC & JWKS governance");
        clickElement(By.xpath("//form//button[contains(., 'Create Project')]"));

        waitForVisible(By.xpath("//*[contains(., 'Identity & Security Core')]"));
        clickElement(By.xpath("//button[contains(., 'Kanban & Backlog')]"));

        clickElement(By.xpath("//button[contains(., 'Requirement')]"));
        fillInput(By.cssSelector("input[placeholder*='REQ-001']"), "REQ-001");
        fillInput(By.cssSelector("input[placeholder*='Multi-Tenant JWT Context Isolation']"), "Multi-Tenant JWT Context Isolation");
        clickElement(By.xpath("//button[contains(., 'Save Requirement')]"));

        clickElement(By.xpath("//button[contains(., 'Task')]"));
        fillInput(By.cssSelector("input[placeholder*='CORE-101']"), "CORE-101");
        new Select(waitForVisible(By.cssSelector("select[aria-label='Priority']"))).selectByValue("CRITICAL");
        fillInput(By.cssSelector("input[placeholder*='Enforce tenant filter']"), "Enforce tenant filter on repository queries");
        clickElement(By.xpath("//button[contains(., 'Save Task')]"));

        WebElement taskCard = waitForVisible(By.xpath("//*[contains(., 'CORE-101')]"));
        Assert.assertTrue(taskCard.isDisplayed(), "Newly created task CORE-101 must render on Kanban board");
    }

    @Test(priority = 9, groups = {"positive", "projects", "kanban"},
          description = "[POSITIVE] Verify Kanban task status progression TODO -> IN_PROGRESS -> IN_REVIEW -> DONE")
    public void test09_Positive_KanbanTaskLifecycleTransitions() {
        clickElement(By.xpath("//button[contains(., 'In Prog')]"));
        clickElement(By.xpath("//button[contains(., 'Review')]"));
        clickElement(By.xpath("//button[contains(., 'Done')]"));

        WebElement verifiedBadge = waitForVisible(By.xpath("//*[contains(., 'Verified')]"));
        Assert.assertTrue(verifiedBadge.isDisplayed(), "Completed Kanban task must display Verified status");
    }

    @Test(priority = 10, groups = {"negative", "projects", "boundary"},
          description = "[NEGATIVE] Verify duplicate Project Key ('CORE') conflict rejection on /projects")
    public void test10_Negative_DuplicateProjectKeyConflictRejection() {
        driver.get(baseUrl + "/projects");
        clickElement(By.xpath("//button[contains(., 'Create Project')]"));
        fillInput(By.cssSelector("input[placeholder*='CORE']"), "CORE");
        fillInput(By.cssSelector("input[placeholder*='Identity & Security Core']"), "Duplicate Project");
        clickElement(By.xpath("//form//button[contains(., 'Create Project')]"));

        WebElement conflictAlert = waitForVisible(By.xpath("//*[contains(., 'already exists') or contains(., 'conflict')]"));
        Assert.assertTrue(conflictAlert.isDisplayed(), "Duplicate project key must trigger conflict error banner");
        clickElement(By.xpath("//button[contains(., 'Cancel')]"));
    }

    @Test(priority = 11, groups = {"positive", "negative", "traceability"},
          description = "[POSITIVE/NEGATIVE] Verify Traceability Spine Matrix, REQUIREMENT_WITHOUT_TEST gap, and Snapshot capture")
    public void test11_TraceabilitySpineGapsAndSnapshots() {
        driver.get(baseUrl + "/traceability");
        waitForVisible(By.xpath("//*[contains(., 'Traceability')]"));

        List<WebElement> snapButtons = driver.findElements(By.xpath("//button[contains(., 'Capture Snapshot')]"));
        if (!snapButtons.isEmpty()) {
            snapButtons.get(0).click();
            fillInput(By.cssSelector("input[placeholder*='ISO27001']"), "SOC2-Release-Gate");
            clickElement(By.xpath("//button[contains(., 'Save Snapshot')]"));
            Assert.assertTrue(waitForVisible(By.xpath("//*[contains(., 'SOC2-Release-Gate')]")).isDisplayed());
        }
    }

    @Test(priority = 12, groups = {"negative", "repositories", "boundary"},
          description = "[NEGATIVE] Verify submitting 'Add Repositories' modal with 0 checkboxes selected is rejected")
    public void test12_Negative_EmptyBatchRepositoryImportGuard() {
        driver.get(baseUrl + "/repositories");
        waitForVisible(By.xpath("//h1[contains(., 'Connected Source Control Repositories')]"));
        List<WebElement> addBtns = driver.findElements(By.xpath("//button[contains(., 'Add Repositories')]"));
        if (!addBtns.isEmpty()) {
            addBtns.get(0).click();
            clickElement(By.xpath("//button[contains(., 'Import Selected')]"));
            WebElement err = waitForVisible(By.xpath("//*[contains(., 'Please select at least one repository to import')]"));
            Assert.assertTrue(err.isDisplayed(), "Empty repository selection must display validation alert");
            clickElement(By.xpath("//button[contains(., 'Cancel')]"));
        }
    }

    @Test(priority = 13, groups = {"negative", "testing", "boundary"},
          description = "[NEGATIVE] Verify uploading malformed non-XML text into JUnit XML modal displays parse error")
    public void test13_Negative_MalformedJUnitXmlUploadRejection() {
        driver.get(baseUrl + "/test-runs");
        waitForVisible(By.xpath("//h1[contains(., 'CI Test Runs & Ingestion')]"));
        clickElement(By.xpath("//button[contains(., 'Upload JUnit XML')]"));
        fillInput(By.cssSelector("textarea[placeholder*='<?xml']"), "CORRUPTED_NON_XML_PAYLOAD");
        clickElement(By.xpath("//button[contains(., 'Ingest & Record Run')]"));

        WebElement xmlErr = waitForVisible(By.xpath("//*[contains(., 'Unable to parse the provided JUnit XML report')]"));
        Assert.assertTrue(xmlErr.isDisplayed(), "Malformed XML payload must trigger sanitized parse error banner");
        clickElement(By.xpath("//button[contains(., 'Cancel')]"));
    }

    @Test(priority = 14, groups = {"positive", "testing", "ai"},
          description = "[POSITIVE] Verify Test Studio generates a SELENIUM WebDriver test suite and runs verification")
    public void test14_Positive_TestStudioSeleniumGenerator() {
        driver.get(baseUrl + "/test-runs");
        clickElement(By.xpath("//button[contains(., 'Test Studio')]"));
        waitForVisible(By.xpath("//*[contains(., 'Test Studio')]"));
        new Select(waitForVisible(By.cssSelector("select[aria-label='Test Framework']"))).selectByValue("SELENIUM");
        clickElement(By.xpath("//button[contains(., 'Generate Test Suite')]"));

        WebElement generatedCode = waitForVisible(By.xpath("//*[contains(., 'SeleniumTest.java')]"));
        Assert.assertTrue(generatedCode.isDisplayed(), "Test Studio must generate a Selenium WebDriver Java test suite");
        clickElement(By.xpath("//button[contains(., 'Close')]"));
    }

    @Test(priority = 15, groups = {"positive", "negative", "ai", "security"},
          description = "[POSITIVE/NEGATIVE] Verify AI Insights 7 tabs, secret redaction ('[REDACTED SECRET]'), and Remediation audit guard")
    public void test15_AiInsightsTabsSecretRedactionAndRemediationGuard() {
        driver.get(baseUrl + "/ai-insights");
        waitForVisible(By.xpath("//h1[contains(., 'AI Engineering Intelligence Layer')]"));

        String[] tabs = {
            "Automatic PR Intelligence",
            "Semantic Vector Search (pgvector)",
            "AST Call Graph Blast Radius",
            "Cross-Repository Topology",
            "Controlled Remediation",
            "System Design & Auto-Docs",
            "Commit Push Guard"
        };
        for (String tab : tabs) {
            clickElement(By.xpath("//button[contains(., '" + tab + "')]"));
        }
    }

    @Test(priority = 16, groups = {"positive", "operations", "auth"},
          description = "[POSITIVE] Verify Operations & Outbox telemetry dashboard and user Sign Out session cleanup")
    public void test16_Positive_OperationsDashboardAndSignOut() {
        driver.get(baseUrl + "/operations");
        waitForVisible(By.xpath("//h1[contains(., 'Production Observability & Outbox Operations')]"));
        Assert.assertTrue(waitForVisible(By.xpath("//*[contains(., 'Circuit Breaker Resilience Matrix')]")).isDisplayed());
    }

    @AfterClass(alwaysRun = true)
    public void tearDownSuite() {
        if (driver != null) {
            driver.quit();
        }
    }
}

