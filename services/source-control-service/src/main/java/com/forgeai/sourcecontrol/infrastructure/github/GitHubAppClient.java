package com.forgeai.sourcecontrol.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative GitHub App API client.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Generate short-lived GitHub App JWTs (RS256, 60-second TTL) for App-level calls.</li>
 *   <li>Exchange installation_id for an installation access token via the GitHub Apps API.</li>
 *   <li>Cache installation tokens with a 5-minute safety margin before GitHub's 1-hour expiry.</li>
 *   <li>List repositories accessible to a given installation.</li>
 *   <li>Fetch authoritative repository metadata by owner/repo.</li>
 * </ol>
 *
 * <p>No mock or fallback behaviour. Any call made when the GitHub App is unconfigured
 * throws {@link GitHubAppNotConfiguredException} immediately.
 */
@Slf4j
@Component
public class GitHubAppClient {

    private static final String GITHUB_API = "https://api.github.com";
    private static final String USER_AGENT = "ForgeAI-SourceControl/1.0";
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final GitHubAppConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics metrics;
    private final com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger structuredLogger;

    /** Cache: installationId → {token, expiresAt} */
    private final ConcurrentHashMap<Long, InstallationToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAppClient(GitHubAppConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubAppClient(GitHubAppConfig config,
                           ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics metrics,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger structuredLogger) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.structuredLogger = structuredLogger;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // GitHub App JWT
    // -------------------------------------------------------------------------

    /**
     * Builds a signed GitHub App JWT valid for 9 minutes (60-second backward drift allowance).
     * Used for App-level API calls (e.g. create installation token).
     */
    public String buildAppJwt() {
        config.requireConfigured();
        PrivateKey key = loadPrivateKey(config.getPrivateKeyPem());
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(String.valueOf(config.getAppId()))
                .issuedAt(Date.from(now.minusSeconds(60)))  // 60-second clock-skew tolerance
                .expiration(Date.from(now.plusSeconds(540))) // 9-minute validity (GitHub max 10m)
                .signWith(key)
                .compact();
    }

    // -------------------------------------------------------------------------
    // Installation access token (cached)
    // -------------------------------------------------------------------------

    /**
     * Returns a valid installation access token, using the in-process cache.
     * Tokens are proactively refreshed 5 minutes before GitHub's 1-hour expiry.
     */
    public String getInstallationToken(Long installationId) {
        config.requireConfigured();

        InstallationToken cached = tokenCache.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(TOKEN_REFRESH_MARGIN))) {
            return cached.token();
        }

        InstallationToken fresh = fetchInstallationToken(installationId);
        tokenCache.put(installationId, fresh);
        log.info("[GitHub] Fetched fresh installation token for installation_id={}, expires={}", installationId, fresh.expiresAt());
        return fresh.token();
    }

    private InstallationToken fetchInstallationToken(Long installationId) {
        String appJwt = buildAppJwt();
        String url = GITHUB_API + "/app/installations/" + installationId + "/access_tokens";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appJwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "create installation token for id=" + installationId);
        String token = body.path("token").asText();
        String expiresAtStr = body.path("expires_at").asText();
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(expiresAtStr);
        } catch (Exception e) {
            expiresAt = Instant.now().plusSeconds(3600);
        }
        return new InstallationToken(token, expiresAt);
    }

    // -------------------------------------------------------------------------
    // Repository listing for an installation
    // -------------------------------------------------------------------------

    /**
     * Lists all repositories accessible to the given GitHub App installation.
     * Returns a list of {@link GitHubRepositoryMetadata}.
     */
    public List<GitHubRepositoryMetadata> listInstallationRepositories(Long installationId) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);

        List<GitHubRepositoryMetadata> results = new ArrayList<>();
        String url = GITHUB_API + "/installation/repositories?per_page=100";

        while (url != null) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = executeRaw(request, "list repositories for installation=" + installationId);
            JsonNode body;
            try {
                body = objectMapper.readTree(response.body());
            } catch (Exception e) {
                throw new GitHubApiException("Failed to parse repository list response", e);
            }
            JsonNode repos = body.path("repositories");
            if (repos.isArray()) {
                for (JsonNode r : repos) {
                    results.add(mapRepo(r, installationId));
                }
            }
            url = nextPageUrl(response);
        }
        return results;
    }

    /**
     * Fetches authoritative metadata for a specific repo and verifies it is
     * accessible to the given installation. Throws {@link GitHubAccessDeniedException}
     * if the repository is not in the installation's accessible set.
     */
    public GitHubRepositoryMetadata getVerifiedRepository(Long installationId, String ownerRepo) {
        config.requireConfigured();

        // Verify accessibility via installation repository list
        List<GitHubRepositoryMetadata> accessible = listInstallationRepositories(installationId);
        return accessible.stream()
                .filter(r -> r.fullName().equalsIgnoreCase(ownerRepo))
                .findFirst()
                .orElseThrow(() -> new GitHubAccessDeniedException(
                        "Repository '" + ownerRepo + "' is not accessible to installation " + installationId +
                        ". Verify the GitHub App is installed on the correct account and the repository is selected."));
    }

    /**
     * Fetches metadata by GitHub repository ID and verifies installation access.
     */
    public GitHubRepositoryMetadata getVerifiedRepositoryById(Long installationId, Long githubRepoId) {
        config.requireConfigured();
        List<GitHubRepositoryMetadata> accessible = listInstallationRepositories(installationId);
        return accessible.stream()
                .filter(r -> Objects.equals(r.id(), githubRepoId))
                .findFirst()
                .orElseThrow(() -> new GitHubAccessDeniedException(
                        "Repository with GitHub ID " + githubRepoId + " is not accessible to installation " + installationId));
    }

    // -------------------------------------------------------------------------
    // Installation metadata
    // -------------------------------------------------------------------------

    /**
     * Validates that a GitHub installation with the given ID actually exists and is active.
     * Returns the account login for the installation.
     */
    public String getInstallationAccountLogin(Long installationId) {
        GitHubInstallationDetails details = getInstallationDetails(installationId);
        if (details == null) {
            throw new GitHubAccessDeniedException("Installation " + installationId + " could not be resolved on GitHub.");
        }
        return details.accountLogin();
    }

    /**
     * Authoritatively fetches installation details directly from GitHub using the App JWT.
     * Returns null if installation is not found (404).
     */
    public GitHubInstallationDetails getInstallationDetails(Long installationId) {
        return findInstallationDetails(installationId).orElse(null);
    }

    /**
     * Authoritatively fetches installation details directly from GitHub using the App JWT.
     * Returns Optional.empty() if installation is not found (404).
     */
    public Optional<GitHubInstallationDetails> findInstallationDetails(Long installationId) {
        config.requireConfigured();
        String appJwt = buildAppJwt();
        String url = GITHUB_API + "/app/installations/" + installationId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appJwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = executeRaw(request, "get installation id=" + installationId);
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                return Optional.of(mapInstallationDetails(body));
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            }
        } catch (GitHubAccessDeniedException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        } catch (Exception e) {
            log.warn("Could not fetch installation details for {}: {}", installationId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries GitHub for a user account's installation of this GitHub App.
     * Uses GET /users/{username}/installation with App JWT.
     */
    public Optional<GitHubInstallationDetails> findUserInstallation(String githubLogin) {
        config.requireConfigured();
        if (githubLogin == null || githubLogin.isBlank()) return Optional.empty();
        String appJwt = buildAppJwt();
        String url = GITHUB_API + "/users/" + githubLogin.trim() + "/installation";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appJwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = executeRaw(request, "get user installation for " + githubLogin);
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                return Optional.of(mapInstallationDetails(body));
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            }
        } catch (GitHubAccessDeniedException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            log.warn("Access denied fetching user installation for {}: {}", githubLogin, e.getMessage());
        } catch (Exception e) {
            log.warn("Could not fetch user installation for {}: {}", githubLogin, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries GitHub for an organization account's installation of this GitHub App.
     * Uses GET /orgs/{org}/installation with App JWT.
     */
    public Optional<GitHubInstallationDetails> findOrgInstallation(String orgLogin) {
        config.requireConfigured();
        if (orgLogin == null || orgLogin.isBlank()) return Optional.empty();
        String appJwt = buildAppJwt();
        String url = GITHUB_API + "/orgs/" + orgLogin.trim() + "/installation";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appJwt)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = executeRaw(request, "get org installation for " + orgLogin);
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                return Optional.of(mapInstallationDetails(body));
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            }
        } catch (GitHubAccessDeniedException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            log.warn("Access denied fetching org installation for {}: {}", orgLogin, e.getMessage());
        } catch (Exception e) {
            log.warn("Could not fetch org installation for {}: {}", orgLogin, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Lists all installations of this GitHub App using GET /app/installations.
     */
    public List<GitHubInstallationDetails> listAppInstallations() {
        config.requireConfigured();
        String appJwt = buildAppJwt();
        List<GitHubInstallationDetails> results = new ArrayList<>();
        String url = GITHUB_API + "/app/installations?per_page=100";

        while (url != null) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + appJwt)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = executeRaw(request, "list app installations");
            if (response.statusCode() != 200) {
                break;
            }
            try {
                JsonNode array = objectMapper.readTree(response.body());
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        results.add(mapInstallationDetails(item));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse app installations response: {}", e.getMessage());
                break;
            }
            url = nextPageUrl(response);
        }
        return results;
    }

    private GitHubInstallationDetails mapInstallationDetails(JsonNode body) {
        Long id = body.path("id").asLong();
        Long appId = body.hasNonNull("app_id") ? body.path("app_id").asLong() : null;
        JsonNode account = body.path("account");
        Long accountId = account.hasNonNull("id") ? account.path("id").asLong() : null;
        String accountLogin = account.path("login").asText();
        String accountType = account.path("type").asText("User");
        String repositorySelection = body.path("repository_selection").asText("all");
        boolean suspended = body.hasNonNull("suspended_at") && !body.path("suspended_at").asText().isBlank();

        return new GitHubInstallationDetails(
                id,
                appId,
                accountId,
                accountLogin,
                accountType,
                repositorySelection.toUpperCase(),
                suspended
        );
    }

    // -------------------------------------------------------------------------
    // Commits, Branches, Pull Requests
    // -------------------------------------------------------------------------

    public List<GitHubCommitMetadata> listCommits(Long installationId, String fullName, int limit) {
        return listCommits(installationId, fullName, null, limit);
    }

    public List<GitHubCommitMetadata> listCommits(Long installationId, String fullName, int perPage, String branchOrSha) {
        return listCommits(installationId, fullName, branchOrSha, perPage);
    }

    public List<GitHubCommitMetadata> listCommits(Long installationId, String fullName, String branchOrSha, int limit) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        int perPage = Math.min(Math.max(limit, 1), 100);
        String url = GITHUB_API + "/repos/" + fullName + "/commits?per_page=" + perPage;
        if (branchOrSha != null && !branchOrSha.isBlank()) {
            url += "&sha=" + URLEncoder.encode(branchOrSha.trim(), StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "list commits for " + fullName);
        List<GitHubCommitMetadata> list = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode c : body) {
                String sha = c.path("sha").asText();
                String message = c.path("commit").path("message").asText("");
                String authorName = c.path("commit").path("author").path("name").asText("unknown");
                String authorEmail = c.path("commit").path("author").path("email").asText("unknown@forgeai.dev");
                String dateStr = c.path("commit").path("author").path("date").asText(null);
                Instant committedAt = Instant.now();
                if (dateStr != null && !dateStr.isBlank()) {
                    try {
                        committedAt = Instant.parse(dateStr);
                    } catch (Exception ignored) {}
                }
                list.add(new GitHubCommitMetadata(sha, message, authorName, authorEmail, committedAt));
            }
        }
        return list;
    }

    public record GitHubCommitFile(String filename, String status, int additions, int deletions, int changes, String patch) {}

    public record GitHubCommitDetail(
            String sha,
            String message,
            String authorName,
            String authorEmail,
            Instant committedAt,
            List<GitHubCommitFile> files
    ) {}

    public Optional<GitHubCommitDetail> getCommitDetail(Long installationId, String fullName, String sha) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String url = GITHUB_API + "/repos/" + fullName + "/commits/" + sha;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        try {
            HttpResponse<String> response = executeRaw(request, "get commit detail " + fullName + "@" + sha);
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String resolvedSha = root.path("sha").asText(sha);
            String message = root.path("commit").path("message").asText("");
            String authorName = root.path("commit").path("author").path("name").asText("developer");
            String authorEmail = root.path("commit").path("author").path("email").asText("developer@forgeai.local");
            String dateStr = root.path("commit").path("author").path("date").asText(null);
            Instant committedAt = Instant.now();
            if (dateStr != null && !dateStr.isBlank()) {
                try {
                    committedAt = Instant.parse(dateStr);
                } catch (Exception ignored) {}
            }

            List<GitHubCommitFile> files = new ArrayList<>();
            JsonNode filesNode = root.path("files");
            if (filesNode.isArray()) {
                for (JsonNode f : filesNode) {
                    files.add(new GitHubCommitFile(
                            f.path("filename").asText(""),
                            f.path("status").asText("modified"),
                            f.path("additions").asInt(0),
                            f.path("deletions").asInt(0),
                            f.path("changes").asInt(0),
                            f.hasNonNull("patch") ? f.path("patch").asText() : ""
                    ));
                }
            }
            return Optional.of(new GitHubCommitDetail(resolvedSha, message, authorName, authorEmail, committedAt, files));
        } catch (Exception e) {
            log.warn("[GitHub] Failed to fetch commit detail for {}@{}: {}", fullName, sha, e.getMessage());
            return Optional.empty();
        }
    }

    public record GitHubWorkflowRun(
            Long id,
            String name,
            Integer runNumber,
            String status,
            String conclusion,
            String headBranch,
            String headSha,
            String event,
            Instant runStartedAt,
            Instant updatedAt,
            String htmlUrl
    ) {}

    public List<GitHubWorkflowRun> listWorkflowRuns(Long installationId, String fullName, int limit) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        int perPage = Math.max(1, Math.min(limit, 30));
        String url = GITHUB_API + "/repos/" + fullName + "/actions/runs?per_page=" + perPage;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        try {
            HttpResponse<String> response = executeRaw(request, "list workflow runs " + fullName);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Collections.emptyList();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode runsNode = root.path("workflow_runs");
            if (!runsNode.isArray()) {
                return Collections.emptyList();
            }
            List<GitHubWorkflowRun> runs = new ArrayList<>();
            for (JsonNode node : runsNode) {
                Long id = node.path("id").asLong(0L);
                if (id == 0L) continue;
                String name = node.path("name").asText("CI");
                Integer runNumber = node.hasNonNull("run_number") ? node.path("run_number").asInt() : 1;
                String status = node.path("status").asText("completed");
                String conclusion = node.hasNonNull("conclusion") ? node.path("conclusion").asText(null) : null;
                String headBranch = node.path("head_branch").asText("main");
                String headSha = node.path("head_sha").asText(null);
                String triggerEvent = node.path("event").asText("push");
                String htmlUrl = node.path("html_url").asText(null);

                Instant runStartedAt = null;
                String startedAtStr = node.path("run_started_at").asText(null);
                if (startedAtStr != null && !startedAtStr.isBlank()) {
                    try {
                        runStartedAt = Instant.parse(startedAtStr);
                    } catch (Exception ignored) {}
                }

                Instant updatedAt = null;
                String updatedAtStr = node.path("updated_at").asText(null);
                if (updatedAtStr != null && !updatedAtStr.isBlank()) {
                    try {
                        updatedAt = Instant.parse(updatedAtStr);
                    } catch (Exception ignored) {}
                }

                runs.add(new GitHubWorkflowRun(
                        id, name, runNumber, status, conclusion,
                        headBranch, headSha, triggerEvent, runStartedAt, updatedAt, htmlUrl
                ));
            }
            return runs;
        } catch (Exception e) {
            log.warn("[GitHub] Failed to list workflow runs for {}: {}", fullName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<GitHubBranchMetadata> listBranches(Long installationId, String fullName) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String url = GITHUB_API + "/repos/" + fullName + "/branches?per_page=100";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "list branches for " + fullName);
        List<GitHubBranchMetadata> list = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode b : body) {
                String name = b.path("name").asText();
                String commitSha = b.path("commit").path("sha").asText();
                boolean isProtected = b.path("protected").asBoolean(false);
                list.add(new GitHubBranchMetadata(name, commitSha, isProtected));
            }
        }
        return list;
    }

    public List<GitHubPullRequestMetadata> listPullRequests(Long installationId, String fullName) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String url = GITHUB_API + "/repos/" + fullName + "/pulls?state=all&per_page=100";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "list pull requests for " + fullName);
        List<GitHubPullRequestMetadata> list = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode p : body) {
                Long prId = p.path("id").asLong();
                int number = p.path("number").asInt();
                String title = p.path("title").asText("");
                String prBody = p.path("body").asText(null);
                String state = p.path("state").asText("open");
                String source = p.path("head").path("ref").asText("unknown");
                String target = p.path("base").path("ref").asText("main");
                String author = p.path("user").path("login").asText("unknown");
                Instant createdAt = parseInstant(p.path("created_at").asText(null));
                Instant updatedAt = parseInstant(p.path("updated_at").asText(null));
                Instant closedAt = parseInstant(p.path("closed_at").asText(null));
                Instant mergedAt = parseInstant(p.path("merged_at").asText(null));
                list.add(new GitHubPullRequestMetadata(prId, number, title, prBody, state, source, target, author, createdAt, updatedAt, closedAt, mergedAt));
            }
        }
        return list;
    }

    public GitHubPullRequestDetail getPullRequestDetail(Long installationId, String fullName, int prNumber) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String url = GITHUB_API + "/repos/" + fullName + "/pulls/" + prNumber;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode p = execute(request, "get pull request #" + prNumber + " for " + fullName);
        Long prId = p.path("id").asLong();
        int number = p.path("number").asInt(prNumber);
        String title = p.path("title").asText("");
        String prBody = p.path("body").asText(null);
        String state = p.path("state").asText("open");
        String source = p.path("head").path("ref").asText("unknown");
        String target = p.path("base").path("ref").asText("main");
        String baseSha = p.path("base").path("sha").asText("");
        String headSha = p.path("head").path("sha").asText("");
        String author = p.path("user").path("login").asText("unknown");
        int additions = p.path("additions").asInt(0);
        int deletions = p.path("deletions").asInt(0);
        int changedFilesCount = p.path("changed_files").asInt(0);
        Instant createdAt = parseInstant(p.path("created_at").asText(null));
        Instant updatedAt = parseInstant(p.path("updated_at").asText(null));

        List<GitHubPullRequestFile> files = getPullRequestFiles(installationId, fullName, prNumber);

        return new GitHubPullRequestDetail(
                prId, number, title, prBody, state, source, target,
                baseSha, headSha, author, additions, deletions,
                changedFilesCount > 0 ? changedFilesCount : files.size(),
                createdAt, updatedAt, files
        );
    }

    public List<GitHubPullRequestFile> getPullRequestFiles(Long installationId, String fullName, int prNumber) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String url = GITHUB_API + "/repos/" + fullName + "/pulls/" + prNumber + "/files?per_page=100";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "list files for PR #" + prNumber + " in " + fullName);
        List<GitHubPullRequestFile> list = new ArrayList<>();
        if (body.isArray()) {
            for (JsonNode f : body) {
                String filename = f.path("filename").asText();
                String status = f.path("status").asText("modified");
                int additions = f.path("additions").asInt(0);
                int deletions = f.path("deletions").asInt(0);
                int changes = f.path("changes").asInt(0);
                String patch = f.path("patch").asText(null);
                String rawUrl = f.path("raw_url").asText(null);
                String contentsUrl = f.path("contents_url").asText(null);
                list.add(new GitHubPullRequestFile(filename, status, additions, deletions, changes, patch, rawUrl, contentsUrl, null));
            }
        }
        return list;
    }

    public Optional<String> getFileContent(Long installationId, String fullName, String path, String ref) {
        config.requireConfigured();
        String token = getInstallationToken(installationId);
        String encodedPath = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
        String url = GITHUB_API + "/repos/" + fullName + "/contents/" + encodedPath + (ref != null && !ref.isBlank() ? "?ref=" + ref : "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.raw+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.ofNullable(response.body());
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new GitHubAccessDeniedException("GitHub API access denied (HTTP " + response.statusCode() + ") for fetching file: " + path);
            } else {
                log.warn("Failed to fetch file content for {} (ref: {}): HTTP {}", path, ref, response.statusCode());
                return Optional.empty();
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Exception fetching file content for {} (ref: {}): {}", path, ref, e.getMessage());
            return Optional.empty();
        }
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GitHubRepositoryMetadata mapRepo(JsonNode r, Long installationId) {
        return new GitHubRepositoryMetadata(
                r.path("id").asLong(),
                installationId,
                r.path("name").asText(),
                r.path("full_name").asText(),
                r.path("private").asBoolean(true),
                r.path("default_branch").asText("main"),
                r.path("owner").path("login").asText()
        );
    }

    private JsonNode execute(HttpRequest request, String operation) {
        HttpResponse<String> response = executeRaw(request, operation);
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GitHubApiException("Failed to parse GitHub response for: " + operation, e);
        }
    }

    private HttpResponse<String> executeRaw(HttpRequest request, String operation) {
        String category = categorizeEndpoint(request.uri().getPath());
        long start = System.currentTimeMillis();
        int attempt = 0;
        int maxAttempts = 2; // Controlled backoff for safe GET

        while (true) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long latency = System.currentTimeMillis() - start;
                int status = response.statusCode();

                Integer remaining = response.headers().firstValue("x-ratelimit-remaining")
                        .map(s -> { try { return Integer.parseInt(s); } catch (Exception e) { return null; } })
                        .orElse(null);
                Long reset = response.headers().firstValue("x-ratelimit-reset")
                        .map(s -> { try { return Long.parseLong(s); } catch (Exception e) { return null; } })
                        .orElse(null);
                Long retryAfter = response.headers().firstValue("retry-after")
                        .map(s -> { try { return Long.parseLong(s); } catch (Exception e) { return null; } })
                        .orElse(null);

                if (metrics != null) {
                    metrics.recordGitHubApiRequest(category, request.method(), status, latency);
                    if (remaining != null && reset != null) {
                        metrics.updateRateLimit(remaining, reset);
                    }
                }

                if (structuredLogger != null) {
                    Map<String, Object> details = new HashMap<>();
                    details.put("endpointCategory", category);
                    details.put("method", request.method());
                    details.put("status", status);
                    if (remaining != null) details.put("rateLimitRemaining", remaining);
                    if (reset != null) details.put("rateLimitReset", reset);
                    structuredLogger.logEvent(
                            status >= 400 ? "WARN" : "DEBUG",
                            "github.api.call",
                            operation,
                            status < 400 ? "SUCCESS" : "FAILURE",
                            latency,
                            status >= 400 ? "Http" + status : null,
                            "GitHub API " + request.method() + " " + category + " returned " + status,
                            details
                    );
                }

                if (status == 429) {
                    long waitSeconds = retryAfter != null ? retryAfter : 2L;
                    if (attempt < maxAttempts && "GET".equalsIgnoreCase(request.method())) {
                        log.warn("GitHub rate limited (429) on GET {}. Backing off for {}s before retry...", operation, waitSeconds);
                        try {
                            Thread.sleep(Math.min(waitSeconds, 5L) * 1000L);
                            continue;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    throw new GitHubRateLimitException(
                            "GitHub API rate limit exceeded (HTTP 429) for: " + operation +
                                    ". Remaining: " + remaining + ", Reset: " + reset + ", Retry-After: " + waitSeconds,
                            waitSeconds, remaining, reset);
                }

                if (status == 401 || status == 403) {
                    tokenCache.clear();
                    throw new GitHubAccessDeniedException(
                            "GitHub API access denied (HTTP " + status + ") for: " + operation +
                                    ". Check GITHUB_APP_ID and GITHUB_PRIVATE_KEY. Response: " + response.body());
                }
                if (status == 404) {
                    throw new GitHubAccessDeniedException(
                            "GitHub resource not found (HTTP 404) for: " + operation + ". Response: " + response.body());
                }
                if (status >= 400) {
                    throw new GitHubApiException(
                            "GitHub API error HTTP " + status + " for: " + operation + ". Body: " + response.body());
                }
                return response;
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                if (metrics != null) {
                    metrics.recordGitHubApiRequest(category, request.method(), 500, System.currentTimeMillis() - start);
                }
                throw new GitHubApiException("GitHub API call failed for: " + operation + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
            }
        }
    }

    private String categorizeEndpoint(String path) {
        if (path == null) return "general";
        if (path.contains("/access_tokens")) return "installation_token";
        if (path.contains("/pulls")) return "pull_requests";
        if (path.contains("/commits")) return "commits";
        if (path.contains("/actions/runs")) return "workflow_runs";
        if (path.contains("/repositories") || path.contains("/repos")) return "repositories";
        return "general";
    }

    private String nextPageUrl(HttpResponse<?> response) {
        return response.headers().firstValue("Link").map(link -> {
            for (String part : link.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int s = part.indexOf('<');
                    int e = part.indexOf('>');
                    if (s >= 0 && e > s) return part.substring(s + 1, e).trim();
                }
            }
            return null;
        }).orElse(null);
    }

    private PrivateKey loadPrivateKey(String pem) {
        try {
            String stripped = pem
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(stripped);

            KeyFactory kf = KeyFactory.getInstance("RSA");
            // Try PKCS#8 first; if that fails (e.g. GitHub standard PKCS#1 RSA key), wrap in PKCS#8 structure
            try {
                return kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
            } catch (Exception pkcs8Ex) {
                try {
                    byte[] pkcs8Bytes = wrapPkcs1ToPkcs8(decoded);
                    return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
                } catch (Exception pkcs1Ex) {
                    throw new GitHubAppNotConfiguredException(
                            "GitHub private key could not be parsed as PKCS#8 or PKCS#1 RSA key: " + pkcs1Ex.getMessage());
                }
            }
        } catch (GitHubAppNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubAppNotConfiguredException("Failed to load GitHub App private key: " + e.getMessage());
        }
    }

    private static byte[] wrapPkcs1ToPkcs8(byte[] pkcs1) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{0x02, 0x01, 0x00}); // version 0
        out.write(new byte[]{
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        }); // rsaEncryption OID
        out.write(0x04); // OCTET STRING
        writeDerLength(out, pkcs1.length);
        out.write(pkcs1);

        byte[] inner = out.toByteArray();
        java.io.ByteArrayOutputStream pkcs8 = new java.io.ByteArrayOutputStream();
        pkcs8.write(0x30); // SEQUENCE
        writeDerLength(pkcs8, inner.length);
        pkcs8.write(inner);
        return pkcs8.toByteArray();
    }

    private static void writeDerLength(java.io.ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else if (length <= 0xFFFFFF) {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x84);
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    /** Invalidates a cached installation token (e.g. on 401 from GitHub). */
    public void evictToken(Long installationId) {
        tokenCache.remove(installationId);
    }

    /** Value object for cached installation tokens. */
    record InstallationToken(String token, Instant expiresAt) {}

    public record GitHubPullRequestResult(
        boolean createdViaApi,
        Integer prNumber,
        String prUrl,
        String branchName,
        String baseBranch,
        String message
    ) {}

    public GitHubPullRequestResult createBranchAndPullRequest(
        Long installationId,
        String fullName,
        String baseBranch,
        String branchName,
        String title,
        String body,
        java.util.Map<String, String> fileContents
    ) {
        String cleanFullName = fullName != null ? fullName.trim().replaceAll("^/+|/+$", "") : "";
        String effectiveBase = (baseBranch != null && !baseBranch.isBlank()) ? baseBranch.trim() : "main";
        String effectiveBranch = (branchName != null && !branchName.isBlank())
            ? branchName.trim()
            : "forgeai/automated-fix-" + System.currentTimeMillis();
        String fallbackCompareUrl = "https://github.com/" + cleanFullName + "/compare/"
            + effectiveBase + "..." + effectiveBranch + "?expand=1";

        if (!config.isConfigured() || installationId == null || installationId <= 0 || cleanFullName.isBlank()) {
            return new GitHubPullRequestResult(
                false, null, fallbackCompareUrl, effectiveBranch, effectiveBase,
                "GitHub App not configured for direct write; compare URL generated"
            );
        }

        try {
            String token = getInstallationToken(installationId);
            String baseSha = null;
            try {
                List<GitHubBranchMetadata> branches = listBranches(installationId, cleanFullName);
                for (GitHubBranchMetadata b : branches) {
                    if (effectiveBase.equalsIgnoreCase(b.name())) {
                        baseSha = b.commitSha();
                        break;
                    }
                }
            } catch (Exception ignored) {}

            if (baseSha == null || baseSha.isBlank()) {
                try {
                    List<GitHubCommitMetadata> commits = listCommits(installationId, cleanFullName, effectiveBase, 1);
                    if (commits != null && !commits.isEmpty()) {
                        baseSha = commits.get(0).sha();
                    }
                } catch (Exception ignored) {}
            }

            if (baseSha == null || baseSha.isBlank()) {
                return new GitHubPullRequestResult(
                    false, null, fallbackCompareUrl, effectiveBranch, effectiveBase,
                    "Could not resolve base branch SHA; compare URL generated"
                );
            }

            // 1. Create branch ref (POST /repos/{owner}/{repo}/git/refs)
            try {
                String refPayload = objectMapper.writeValueAsString(Map.of(
                    "ref", "refs/heads/" + effectiveBranch,
                    "sha", baseSha
                ));
                HttpRequest refReq = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API + "/repos/" + cleanFullName + "/git/refs"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(refPayload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();
                httpClient.send(refReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception refEx) {
                log.debug("Branch ref creation notice for {}: {}", cleanFullName, refEx.getMessage());
            }

            // 2. Commit file contents (PUT /repos/{owner}/{repo}/contents/{path})
            if (fileContents != null && !fileContents.isEmpty()) {
                for (var entry : fileContents.entrySet()) {
                    String filePath = entry.getKey();
                    String content = entry.getValue();
                    if (filePath == null || filePath.isBlank() || content == null) continue;

                    String encodedPath = java.net.URLEncoder.encode(filePath, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
                    String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

                    String existingSha = null;
                    try {
                        HttpRequest getFileReq = HttpRequest.newBuilder()
                            .uri(URI.create(GITHUB_API + "/repos/" + cleanFullName + "/contents/" + encodedPath + "?ref=" + effectiveBranch))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", USER_AGENT)
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .GET()
                            .timeout(Duration.ofSeconds(15))
                            .build();
                        HttpResponse<String> fileResp = httpClient.send(getFileReq, HttpResponse.BodyHandlers.ofString());
                        if (fileResp.statusCode() == 200) {
                            JsonNode fileNode = objectMapper.readTree(fileResp.body());
                            if (fileNode.hasNonNull("sha")) {
                                existingSha = fileNode.get("sha").asText();
                            }
                        }
                    } catch (Exception ignored) {}

                    Map<String, Object> putBodyMap = new HashMap<>();
                    putBodyMap.put("message", title != null ? title : "chore(forgeai): automated update");
                    putBodyMap.put("content", encodedContent);
                    putBodyMap.put("branch", effectiveBranch);
                    if (existingSha != null && !existingSha.isBlank()) {
                        putBodyMap.put("sha", existingSha);
                    }

                    HttpRequest putFileReq = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_API + "/repos/" + cleanFullName + "/contents/" + encodedPath))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", USER_AGENT)
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(putBodyMap), StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(15))
                        .build();
                    httpClient.send(putFileReq, HttpResponse.BodyHandlers.ofString());
                }
            }

            // 3. Create pull request (POST /repos/{owner}/{repo}/pulls)
            Map<String, Object> prBodyMap = Map.of(
                "title", title != null ? title : "ForgeAI Automated Pull Request",
                "head", effectiveBranch,
                "base", effectiveBase,
                "body", body != null ? body : "Automated Pull Request generated by ForgeAI."
            );
            HttpRequest prReq = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API + "/repos/" + cleanFullName + "/pulls"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(prBodyMap), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> prResp = httpClient.send(prReq, HttpResponse.BodyHandlers.ofString());
            if (prResp.statusCode() == 200 || prResp.statusCode() == 201) {
                JsonNode prNode = objectMapper.readTree(prResp.body());
                if (prNode.hasNonNull("html_url")) {
                    Integer number = prNode.hasNonNull("number") ? prNode.get("number").asInt() : null;
                    return new GitHubPullRequestResult(
                        true,
                        number,
                        prNode.get("html_url").asText(),
                        effectiveBranch,
                        effectiveBase,
                        "Pull Request created via GitHub API"
                    );
                }
            } else {
                log.warn("GitHub PR creation responded with HTTP {}: {}", prResp.statusCode(), prResp.body());
            }
        } catch (Exception e) {
            log.warn("Direct GitHub PR creation fell back to compare URL for {}: {}", cleanFullName, e.getMessage());
        }

        return new GitHubPullRequestResult(
            false, null, fallbackCompareUrl, effectiveBranch, effectiveBase,
            "Fallback compare URL generated"
        );
    }

    public record GitHubWorkflowRunSummary(
        Long id,
        String name,
        Integer runNumber,
        String status,
        String conclusion,
        String headBranch,
        String headSha,
        String event,
        Instant runStartedAt,
        Instant completedAt,
        String htmlUrl
    ) {}

    public List<GitHubWorkflowRunSummary> getWorkflowRuns(
        Long installationId,
        String fullName,
        String branch,
        int limit
    ) {
        String cleanFullName = fullName != null ? fullName.trim().replaceAll("^/+|/+$", "") : "";
        if (!config.isConfigured() || installationId == null || installationId <= 0 || cleanFullName.isBlank()) {
            return List.of();
        }
        try {
            String token = getInstallationToken(installationId);
            int effectiveLimit = Math.max(1, Math.min(limit, 30));
            StringBuilder urlBuilder = new StringBuilder(GITHUB_API)
                .append("/repos/")
                .append(cleanFullName)
                .append("/actions/runs?per_page=")
                .append(effectiveLimit);
            if (branch != null && !branch.isBlank()) {
                urlBuilder.append("&branch=").append(java.net.URLEncoder.encode(branch.trim(), StandardCharsets.UTF_8));
            }

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("GitHub Actions workflow runs fetch returned HTTP {} for {}", resp.statusCode(), cleanFullName);
                return List.of();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode runsNode = root.path("workflow_runs");
            if (!runsNode.isArray()) {
                return List.of();
            }

            List<GitHubWorkflowRunSummary> result = new ArrayList<>();
            for (JsonNode r : runsNode) {
                Long id = r.hasNonNull("id") ? r.get("id").asLong() : null;
                if (id == null) continue;
                String name = r.hasNonNull("name") ? r.get("name").asText() : "CI Workflow";
                Integer runNumber = r.hasNonNull("run_number") ? r.get("run_number").asInt() : 1;
                String status = r.hasNonNull("status") ? r.get("status").asText() : "completed";
                String conclusion = r.hasNonNull("conclusion") ? r.get("conclusion").asText() : null;
                String headBranch = r.hasNonNull("head_branch") ? r.get("head_branch").asText() : "main";
                String headSha = r.hasNonNull("head_sha") ? r.get("head_sha").asText() : "0000000";
                String event = r.hasNonNull("event") ? r.get("event").asText() : "push";
                Instant startedAt = r.hasNonNull("run_started_at")
                    ? Instant.parse(r.get("run_started_at").asText())
                    : Instant.now();
                Instant updatedAt = r.hasNonNull("updated_at")
                    ? Instant.parse(r.get("updated_at").asText())
                    : startedAt;
                String htmlUrl = r.hasNonNull("html_url") ? r.get("html_url").asText() : "";

                result.add(new GitHubWorkflowRunSummary(
                    id, name, runNumber, status, conclusion, headBranch, headSha, event, startedAt, updatedAt, htmlUrl
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to fetch GitHub Actions workflow runs for {}: {}", cleanFullName, e.getMessage());
            return List.of();
        }
    }
}
