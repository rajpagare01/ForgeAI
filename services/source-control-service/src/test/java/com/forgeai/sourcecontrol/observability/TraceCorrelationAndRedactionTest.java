package com.forgeai.sourcecontrol.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceCorrelationAndRedactionTest {

    @Test
    @DisplayName("Sensitive fields like Bearer tokens, GitHub keys, passwords must be redacted from logs")
    void testSensitiveFieldRedaction() {
        String sensitiveMessage1 = "Authorization header: Bearer ghp_1234567890abcdefghijklmnopqrstuvwxyz";
        String redacted1 = StructuredLogger.maskSecrets(sensitiveMessage1);
        assertThat(redacted1).doesNotContain("ghp_1234567890abcdefghijklmnopqrstuvwxyz");
        assertThat(redacted1).contains("[REDACTED]");

        String sensitiveMessage2 = "Database connecting with password=SuperSecretPassword123! to host";
        String redacted2 = StructuredLogger.maskSecrets(sensitiveMessage2);
        assertThat(redacted2).doesNotContain("SuperSecretPassword123!");
        assertThat(redacted2).contains("[REDACTED]");

        String sensitiveMessage3 = "Using api_key=AIzaSyD-1234567890abcdefg";
        String redacted3 = StructuredLogger.maskSecrets(sensitiveMessage3);
        assertThat(redacted3).doesNotContain("AIzaSyD-1234567890abcdefg");
        assertThat(redacted3).contains("[REDACTED]");

        String sensitiveMessage4 = "Loaded key: -----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC6...\n-----END PRIVATE KEY-----";
        String redacted4 = StructuredLogger.maskSecrets(sensitiveMessage4);
        assertThat(redacted4).doesNotContain("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC6");
        assertThat(redacted4).contains("[REDACTED]");

        String sensitiveMessage5 = "token=fake-test-token and password=fake-password and Bearer TESTTOKEN123456";
        String redacted5 = StructuredLogger.maskSecrets(sensitiveMessage5);
        assertThat(redacted5).doesNotContain("fake-test-token");
        assertThat(redacted5).doesNotContain("fake-password");
        assertThat(redacted5).doesNotContain("TESTTOKEN123456");
        assertThat(redacted5).contains("[REDACTED]");
    }

    @Test
    @DisplayName("Null or blank strings should be returned safely without error")
    void testBlankStringSafety() {
        assertThat(StructuredLogger.maskSecrets(null)).isNull();
        assertThat(StructuredLogger.maskSecrets("")).isEqualTo("");
        assertThat(StructuredLogger.maskSecrets("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("Non-sensitive messages should remain intact")
    void testNonSensitiveMessagePreserved() {
        String normal = "Repository synced successfully: repoId=123, commits=5";
        assertThat(StructuredLogger.maskSecrets(normal)).isEqualTo(normal);
    }
}
