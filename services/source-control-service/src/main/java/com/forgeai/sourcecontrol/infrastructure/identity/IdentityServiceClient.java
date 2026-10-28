package com.forgeai.sourcecontrol.infrastructure.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Client to query authoritative user identity and linked GitHub accounts from Identity Service.
 */
@Slf4j
@Component
public class IdentityServiceClient {

    private final String identityServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public IdentityServiceClient(
            @Value("${forgeai.services.identity-service.url:${IDENTITY_SERVICE_URL:${IDENTITY_SERVICE_URI:http://identity-service:8081}}}") String identityServiceUrl,
            ObjectMapper objectMapper) {
        this.identityServiceUrl = identityServiceUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record UserGitHubProfile(
            boolean connected,
            Long githubUserId,
            String githubLogin,
            String name,
            String email,
            String avatarUrl
    ) {}

    /**
     * Resolves the linked GitHub identity for the given ForgeAI user.
     */
    public Optional<UserGitHubProfile> getUserGitHubProfile(UUID userId) {
        if (userId == null) return Optional.empty();

        String url = identityServiceUrl + "/api/v1/auth/github/status";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("X-User-Id", userId.toString())
                .header("X-ForgeAI-Internal", "attested")
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                boolean connected = body.path("connected").asBoolean(false) || body.path("linked").asBoolean(false);
                if (!connected) {
                    return Optional.of(new UserGitHubProfile(false, null, null, null, null, null));
                }
                JsonNode idNode = body.hasNonNull("identity") ? body.path("identity") : body;
                Long ghUserId = idNode.hasNonNull("githubUserId") ? idNode.path("githubUserId").asLong() : null;
                String ghLogin = idNode.path("githubLogin").asText(null);
                String name = idNode.path("name").asText(null);
                String email = idNode.path("email").asText(null);
                String avatarUrl = idNode.path("avatarUrl").asText(null);

                return Optional.of(new UserGitHubProfile(true, ghUserId, ghLogin, name, email, avatarUrl));
            } else {
                log.warn("[Identity Client] Received HTTP {} fetching GitHub status for user {}", response.statusCode(), userId);
            }
        } catch (Exception e) {
            log.warn("[Identity Client] Failed to fetch GitHub status for user {}: {}", userId, e.getMessage());
        }
        return Optional.empty();
    }
}
