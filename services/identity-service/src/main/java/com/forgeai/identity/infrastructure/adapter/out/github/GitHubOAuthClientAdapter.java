package com.forgeai.identity.infrastructure.adapter.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.application.port.out.GitHubOAuthClient;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GitHubOAuthClientAdapter implements GitHubOAuthClient {

    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GitHubOAuthClientAdapter(
            @Value("${forgeai.github.client-id:${GITHUB_CLIENT_ID:}}") String clientId,
            @Value("${forgeai.github.client-secret:${GITHUB_CLIENT_SECRET:}}") String clientSecret,
            ObjectMapper objectMapper) {
        this.clientId = clientId != null ? clientId.trim() : "";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "";
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String getClientId() {
        return clientId;
    }

    @Override
    public OAuthToken exchangeCodeForToken(String code, String redirectUri) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub OAuth is not configured. Missing client-id or client-secret.");
        }

        try {
            Map<String, String> bodyMap = new HashMap<>();
            bodyMap.put("client_id", clientId);
            bodyMap.put("client_secret", clientSecret);
            bodyMap.put("code", code);
            if (redirectUri != null && !redirectUri.isBlank()) {
                bodyMap.put("redirect_uri", redirectUri);
            }

            String requestBody = objectMapper.writeValueAsString(bodyMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com/login/oauth/access_token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "ForgeAI-Identity-Service")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("GitHub token exchange failed with HTTP status: {}", response.statusCode());
                throw new InvalidCredentialsException("GitHub OAuth token exchange failed with status: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("error")) {
                String error = root.path("error").asText();
                String desc = root.path("error_description").asText(error);
                log.warn("GitHub OAuth error during code exchange: {} ({})", error, desc);
                throw new InvalidCredentialsException("GitHub OAuth error: " + desc);
            }

            String accessToken = root.path("access_token").asText();
            String tokenType = root.path("token_type").asText("bearer");
            String scope = root.path("scope").asText("");

            if (accessToken == null || accessToken.isBlank()) {
                throw new InvalidCredentialsException("No access token returned by GitHub");
            }

            return new OAuthToken(accessToken, tokenType, scope);
        } catch (InvalidCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Exception during GitHub token exchange", e);
            throw new RuntimeException("Failed to exchange code with GitHub", e);
        }
    }

    @Override
    public UserProfile fetchUserProfile(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "ForgeAI-Identity-Service")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Failed to fetch GitHub user profile, HTTP status: {}", response.statusCode());
                throw new InvalidCredentialsException("Failed to fetch GitHub user profile");
            }

            JsonNode root = objectMapper.readTree(response.body());
            Long id = root.path("id").asLong();
            String login = root.path("login").asText();
            String name = root.hasNonNull("name") ? root.path("name").asText() : null;
            String email = root.hasNonNull("email") ? root.path("email").asText() : null;
            String avatarUrl = root.hasNonNull("avatar_url") ? root.path("avatar_url").asText() : null;
            String htmlUrl = root.hasNonNull("html_url") ? root.path("html_url").asText() : null;

            return new UserProfile(id, login, name, email, avatarUrl, htmlUrl);
        } catch (Exception e) {
            log.error("Exception fetching GitHub user profile", e);
            throw new RuntimeException("Failed to fetch GitHub user profile", e);
        }
    }

    @Override
    public List<UserEmail> fetchUserEmails(String accessToken) {
        List<UserEmail> emails = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/emails"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "ForgeAI-Identity-Service")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String email = node.path("email").asText();
                        boolean primary = node.path("primary").asBoolean(false);
                        boolean verified = node.path("verified").asBoolean(false);
                        String visibility = node.path("visibility").asText("");
                        emails.add(new UserEmail(email, primary, verified, visibility));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch user emails from GitHub (non-fatal): {}", e.getMessage());
        }
        return emails;
    }
}
