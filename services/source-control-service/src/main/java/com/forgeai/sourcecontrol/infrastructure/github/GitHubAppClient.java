package com.forgeai.sourcecontrol.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final GitHubAppConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** Cache: installationId → {token, expiresAt} */
    private final ConcurrentHashMap<Long, InstallationToken> tokenCache = new ConcurrentHashMap<>();

    public GitHubAppClient(GitHubAppConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // GitHub App JWT
    // -------------------------------------------------------------------------

    /**
     * Builds a signed GitHub App JWT valid for 60 seconds.
     * Used for App-level API calls (e.g. create installation token).
     */
    public String buildAppJwt() {
        config.requireConfigured();
        PrivateKey key = loadPrivateKey(config.getPrivateKeyPem());
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(String.valueOf(config.getAppId()))
                .issuedAt(Date.from(now.minusSeconds(10)))  // clock-skew tolerance
                .expiration(Date.from(now.plusSeconds(60)))
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
        config.requireConfigured();
        String appJwt = buildAppJwt();
        String url = GITHUB_API + "/app/installations/" + installationId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + appJwt)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        JsonNode body = execute(request, "get installation id=" + installationId);
        String login = body.path("account").path("login").asText();
        if (login.isBlank()) {
            throw new GitHubApiException("GitHub returned empty account.login for installation " + installationId);
        }
        return login;
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
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new GitHubAccessDeniedException(
                        "GitHub API access denied (HTTP " + response.statusCode() + ") for: " + operation +
                        ". Check GITHUB_APP_ID and GITHUB_PRIVATE_KEY.");
            }
            if (response.statusCode() == 404) {
                throw new GitHubAccessDeniedException(
                        "GitHub resource not found (HTTP 404) for: " + operation);
            }
            if (response.statusCode() >= 400) {
                throw new GitHubApiException(
                        "GitHub API error HTTP " + response.statusCode() + " for: " + operation + ". Body: " + response.body());
            }
            return response;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException("GitHub API call failed for: " + operation, e);
        }
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

            // Try PKCS8 first, then fall back to PKCS1 via BouncyCastle if available
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
            } catch (Exception pkcs8Ex) {
                throw new GitHubAppNotConfiguredException(
                        "GitHub private key could not be parsed. Ensure GITHUB_PRIVATE_KEY is a valid PKCS#8 RSA key: " + pkcs8Ex.getMessage());
            }
        } catch (GitHubAppNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubAppNotConfiguredException("Failed to load GitHub App private key: " + e.getMessage());
        }
    }

    /** Invalidates a cached installation token (e.g. on 401 from GitHub). */
    public void evictToken(Long installationId) {
        tokenCache.remove(installationId);
    }

    /** Value object for cached installation tokens. */
    record InstallationToken(String token, Instant expiresAt) {}
}
