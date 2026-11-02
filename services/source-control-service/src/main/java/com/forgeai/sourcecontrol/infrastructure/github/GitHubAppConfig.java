  package com.forgeai.sourcecontrol.infrastructure.github;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds GitHub App credentials sourced from environment variables.
 * <p>
 * Required variables (when GitHub integration is active):
 * <ul>
 *   <li>{@code GITHUB_APP_ID}           – numeric GitHub App ID</li>
 *   <li>{@code GITHUB_PRIVATE_KEY}       – RSA private key in PEM format (newlines as {@code \n})</li>
 *   <li>{@code GITHUB_PRIVATE_KEY_PATH}  – path to RSA private key PEM file (e.g. secrets/github-app-private-key.pem)</li>
 *   <li>{@code GITHUB_WEBHOOK_SECRET}    – shared secret used to compute X-Hub-Signature-256</li>
 * </ul>
 * <p>
 * When any required credential is absent the integration is considered <em>unconfigured</em>.
 * All endpoints that require GitHub will return a 503 with a human-readable explanation.
 */
@Slf4j
@Getter
@Component
public class GitHubAppConfig {

    private final long appId;
    private final String appSlug;
    private volatile String resolvedAppSlug;
    private final String privateKeyPem;
    private final String webhookSecret;
    private final boolean configured;

    public void setResolvedAppSlug(String resolvedAppSlug) {
        if (resolvedAppSlug != null && !resolvedAppSlug.isBlank()) {
            this.resolvedAppSlug = resolvedAppSlug.trim();
        }
    }

    public String getAppSlug() {
        if (resolvedAppSlug != null && !resolvedAppSlug.isBlank()) {
            return resolvedAppSlug;
        }
        return (appSlug != null && !appSlug.isBlank()) ? appSlug : "forgeai";
    }

    public String getAppInstallUrl() {
        return "https://github.com/apps/" + getAppSlug() + "/installations/new";
    }

    public GitHubAppConfig(long appId, String privateKeyPem, String webhookSecret) {
        this(String.valueOf(appId), "forgeai", privateKeyPem, "", webhookSecret);
    }

    public GitHubAppConfig(String appIdRaw, String privateKeyPem, String webhookSecret) {
        this(appIdRaw, "forgeai", privateKeyPem, "", webhookSecret);
    }

    @Autowired
    public GitHubAppConfig(
            @Value("${forgeai.github.app-id:0}") String appIdRaw,
            @Value("${forgeai.github.app-slug:forgeai}") String appSlug,
            @Value("${forgeai.github.private-key:}") String privateKeyPem,
            @Value("${forgeai.github.private-key-path:}") String privateKeyPath,
            @Value("${forgeai.github.webhook-secret:}") String webhookSecret) {

        this.appSlug = (appSlug != null && !appSlug.isBlank()) ? appSlug.trim() : "forgeai";

        long parsedAppId = 0L;
        if (appIdRaw != null && !appIdRaw.isBlank()) {
            try {
                parsedAppId = Long.parseLong(appIdRaw.trim());
            } catch (NumberFormatException e) {
                log.warn("[GitHub] Invalid appId '{}', defaulting to 0", appIdRaw);
                parsedAppId = 0L;
            }
        }
        this.appId = parsedAppId;

        // Resolve private key: direct PEM content takes precedence, then path or standard fallback
        String resolvedKey = "";
        if (privateKeyPem != null && privateKeyPem.contains("BEGIN")) {
            resolvedKey = privateKeyPem.replace("\\n", "\n");
        } else {
            List<String> candidatePaths = new ArrayList<>();
            if (privateKeyPath != null && !privateKeyPath.isBlank()) {
                candidatePaths.add(privateKeyPath.trim());
            }
            candidatePaths.add("secrets/github-app-private-key.pem");
            candidatePaths.add("/app/secrets/github-app-private-key.pem");

            for (String pathStr : candidatePaths) {
                try {
                    Path p = Path.of(pathStr);
                    if (Files.isRegularFile(p)) {
                        String content = Files.readString(p);
                        if (content.contains("BEGIN")) {
                            resolvedKey = content.trim();
                            log.info("[GitHub] Successfully loaded RSA private key from: {}", p.toAbsolutePath());
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[GitHub] Could not read private key from {}: {}", pathStr, e.getMessage());
                }
            }

            // Fallback: scan candidate directories for any *.pem file
            if (resolvedKey.isBlank()) {
                for (String dirStr : List.of("secrets", "/app/secrets")) {
                    try {
                        Path dir = Path.of(dirStr);
                        if (Files.isDirectory(dir)) {
                            try (var stream = Files.list(dir)) {
                                for (Path p : stream.toList()) {
                                    if (p.toString().endsWith(".pem") && Files.isRegularFile(p)) {
                                        String content = Files.readString(p);
                                        if (content.contains("BEGIN")) {
                                            resolvedKey = content.trim();
                                            log.info("[GitHub] Successfully loaded RSA private key from discovered file: {}", p.toAbsolutePath());
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[GitHub] Directory scan in {} failed: {}", dirStr, e.getMessage());
                    }
                    if (!resolvedKey.isBlank()) break;
                }
            }
        }

        this.privateKeyPem = resolvedKey;
        this.webhookSecret = webhookSecret != null ? webhookSecret : "";

        boolean appIdPresent = this.appId > 0;
        boolean keyPresent = !this.privateKeyPem.isBlank() && this.privateKeyPem.contains("BEGIN");
        boolean secretPresent = !this.webhookSecret.isBlank();

        this.configured = appIdPresent && keyPresent && secretPresent;

        if (this.configured) {
            log.info("[GitHub] App configuration loaded successfully: appId={}, appSlug={}", appId, this.appSlug);
        } else {
            log.warn("[GitHub] App NOT configured. Missing: appId={} privateKey={} webhookSecret={}. "
                    + "Repository connection and installation claim endpoints are disabled until credentials are provided.",
                    appIdPresent, keyPresent, secretPresent);
        }
    }

    /**
     * Throws {@link GitHubAppNotConfiguredException} unless all credentials are present.
     */
    public void requireConfigured() {
        if (!configured) {
            throw new GitHubAppNotConfiguredException(
                    "GitHub App is not configured. Set GITHUB_APP_ID, GITHUB_PRIVATE_KEY and GITHUB_WEBHOOK_SECRET.");
        }
    }
}
