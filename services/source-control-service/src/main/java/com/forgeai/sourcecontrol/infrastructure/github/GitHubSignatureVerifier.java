package com.forgeai.sourcecontrol.infrastructure.github;

import com.forgeai.sourcecontrol.domain.exception.InvalidWebhookSignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Component
public class GitHubSignatureVerifier {

    private final String webhookSecret;

    public GitHubSignatureVerifier(@Value("${forgeai.github.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret != null ? webhookSecret.trim() : "";
        if (this.webhookSecret.isBlank()) {
            log.warn("[GitHub Security] Webhook secret is not configured or blank! All incoming GitHub webhooks will be rejected.");
        } else {
            log.info("[GitHub Security] GitHub webhook signature verifier initialized (secret configured, length={}).", this.webhookSecret.length());
        }
    }

    public boolean isSecretConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean isValid(byte[] payloadBytes, String signatureHeader) {
        if (!isSecretConfigured() || signatureHeader == null || signatureHeader.isBlank() || payloadBytes == null) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);

            byte[] computedHash = mac.doFinal(payloadBytes);
            String computedHex = HexFormat.of().formatHex(computedHash);
            String expectedSignature = "sha256=" + computedHex;

            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to calculate HMAC SHA-256 for GitHub webhook", e);
            return false;
        }
    }

    public void verify(byte[] payloadBytes, String signatureHeader) {
        if (!isValid(payloadBytes, signatureHeader)) {
            throw new InvalidWebhookSignatureException("Invalid GitHub webhook signature in X-Hub-Signature-256 header");
        }
    }

    public String computeSignature(byte[] payloadBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payloadBytes);
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute signature", e);
        }
    }
}
