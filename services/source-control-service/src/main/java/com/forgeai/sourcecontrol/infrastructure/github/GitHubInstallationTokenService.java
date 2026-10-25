package com.forgeai.sourcecontrol.infrastructure.github;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dedicated service for managing short-lived GitHub App installation tokens.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Installation access tokens are never logged or stored permanently in database records.</li>
 *   <li>Tokens are cached in-memory with a safety margin before GitHub's 1-hour expiry.</li>
 *   <li>Tokens are generated dynamically via signed GitHub App JWTs (RS256).</li>
 *   <li>Tokens are never exposed to the frontend, error messages, or telemetry.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubInstallationTokenService {

    private final GitHubAppClient gitHubAppClient;

    /**
     * Resolves a valid installation access token for repository operations.
     *
     * @param installationId The verified GitHub installation ID
     * @return Fresh or valid cached bearer token
     */
    public String getInstallationToken(Long installationId) {
        if (installationId == null || installationId <= 0) {
            throw new IllegalArgumentException("Valid installationId is required to obtain installation token");
        }
        return gitHubAppClient.getInstallationToken(installationId);
    }
}
