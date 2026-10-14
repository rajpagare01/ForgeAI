package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookSignatureVerificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitHubSignatureVerifier signatureVerifier;

    @Test
    @DisplayName("WebhookSignatureVerificationTest: Valid HMAC signature succeeds with 202 Accepted")
    void validHmacSucceeds() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        String payload = """
            {
                "action": "ping",
                "zen": "Non-blocking is better than blocking."
            }
            """;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String validSignature = signatureVerifier.computeSignature(payloadBytes);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "ping")
                        .header("X-Hub-Signature-256", validSignature)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.deliveryId").value(deliveryId))
                .andExpect(jsonPath("$.idempotentHit").value(false));
    }

    @Test
    @DisplayName("WebhookSignatureVerificationTest: Tampered signature fails with 401 Unauthorized")
    void tamperedSignatureFailsWith401() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        String payload = "{\"action\":\"ping\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String invalidSignature = "sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "ping")
                        .header("X-Hub-Signature-256", invalidSignature)
                        .content(payloadBytes))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WebhookSignatureVerificationTest: Signature computed with wrong secret fails with 401 Unauthorized")
    void wrongSecretFailsWith401() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        String payload = "{\"action\":\"ping\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // Compute HMAC using an arbitrary wrong secret
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec wrongKey = new javax.crypto.spec.SecretKeySpec(
                "completely-wrong-secret-key-12345".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(wrongKey);
        String wrongSignature = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(payloadBytes));

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "ping")
                        .header("X-Hub-Signature-256", wrongSignature)
                        .content(payloadBytes))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WebhookSignatureVerificationTest: Missing signature header fails with 401 Unauthorized")
    void missingSignatureFailsWith401() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        String payload = "{\"action\":\"ping\"}";

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "ping")
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }
}
