package com.forgeai.sourcecontrol.infrastructure.actuator;

import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("github")
@RequiredArgsConstructor
public class GitHubHealthIndicator implements HealthIndicator {

    private final GitHubAppConfig gitHubAppConfig;
    private final SourceControlMetrics metrics;
    private final GitHubInstallationRepository installationRepository;

    @Override
    public Health health() {
        if (!gitHubAppConfig.isConfigured()) {
            return Health.status("UNCONFIGURED")
                    .withDetail("status", "UNCONFIGURED")
                    .withDetail("configured", false)
                    .withDetail("message", "GitHub App credentials not configured")
                    .build();
        }

        try {
            int remaining = metrics.getRateLimitRemaining();
            if (remaining <= 0) {
                return Health.status("DEGRADED")
                        .withDetail("status", "DEGRADED")
                        .withDetail("configured", true)
                        .withDetail("rateLimitRemaining", 0)
                        .withDetail("message", "GitHub API rate limit exhausted")
                        .build();
            }

            int totalInstallations = installationRepository.findAll().size();

            return Health.up()
                    .withDetail("status", "CONFIGURED")
                    .withDetail("configured", true)
                    .withDetail("appId", gitHubAppConfig.getAppId())
                    .withDetail("totalInstallations", totalInstallations)
                    .withDetail("rateLimitRemaining", remaining)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("status", "UNREACHABLE")
                    .withDetail("configured", true)
                    .withDetail("message", "Failed to communicate with GitHub or persistence: " + e.getMessage())
                    .build();
        }
    }
}
