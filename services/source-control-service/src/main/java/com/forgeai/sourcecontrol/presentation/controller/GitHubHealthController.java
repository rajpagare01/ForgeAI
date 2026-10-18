package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic endpoint reporting GitHub App integration state.
 *
 * <p>Design principles:
 * <ul>
 *   <li>Never exposes secrets (app ID is safe to show; private key is never exposed).</li>
 *   <li>No authentication required so operators can diagnose configuration issues without a JWT.</li>
 *   <li>Includes quarantine count so operators can see unmapped installations awaiting claim.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/github/health")
@RequiredArgsConstructor
public class GitHubHealthController {

    private static final String QUARANTINE_ORG = "00000000-0000-0000-0000-000000000000";

    private final GitHubAppConfig gitHubAppConfig;
    private final GitHubInstallationRepository installationRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> report = new HashMap<>();

        // Config state
        report.put("configured", gitHubAppConfig.isConfigured());
        report.put("appId", gitHubAppConfig.isConfigured() ? gitHubAppConfig.getAppId() : null);
        report.put("privateKeyPresent", !gitHubAppConfig.getPrivateKeyPem().isBlank());
        report.put("webhookSecretPresent", !gitHubAppConfig.getWebhookSecret().isBlank());

        if (!gitHubAppConfig.isConfigured()) {
            report.put("status", "UNCONFIGURED");
            report.put("message",
                    "GitHub integration is not configured. Set GITHUB_APP_ID, GITHUB_PRIVATE_KEY, and GITHUB_WEBHOOK_SECRET.");
            return ResponseEntity.ok(report);
        }

        // Installation state (no auth required for diagnostic info)
        try {
            List<GitHubInstallation> all = installationRepository.findAll();
            long quarantined = all.stream()
                    .filter(i -> QUARANTINE_ORG.equals(i.getOrganizationId().toString()))
                    .count();
            long active = all.stream()
                    .filter(i -> !QUARANTINE_ORG.equals(i.getOrganizationId().toString()))
                    .count();

            report.put("status", "CONFIGURED");
            report.put("totalInstallations", all.size());
            report.put("activeInstallations", active);
            report.put("quarantinedInstallations", quarantined);

            if (quarantined > 0) {
                report.put("quarantineWarning",
                        quarantined + " GitHub installation(s) are quarantined and awaiting org claim via POST /api/v1/installations/claim");
            }
        } catch (Exception e) {
            log.warn("[GitHubHealth] Error querying installations from repository: {}", e.getMessage());
            report.put("status", "CONFIGURED");
            report.put("totalInstallations", 0);
            report.put("activeInstallations", 0);
            report.put("quarantinedInstallations", 0);
            report.put("databaseWarning", "GitHub App credentials configured, but installation state query failed: " + e.getMessage());
        }

        return ResponseEntity.ok(report);
    }
}
