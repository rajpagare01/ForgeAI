package com.forgeai.testing.infrastructure.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class StructuredLogger {

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(-----BEGIN[^\r\n]+-----[\\s\\S]*?-----END[^\r\n]+-----|bearer\\s+[\\w\\-._]+|token[=:]\\s*[\\w\\-._]+|key[=:]\\s*(?!-----)[\\w\\-._]+|password[=:]\\s*[^,\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper objectMapper;
    private final String serviceName;
    private final String environment;

    public StructuredLogger(
            ObjectMapper objectMapper,
            @Value("${spring.application.name:testing-service}") String serviceName,
            @Value("${forgeai.environment:production}") String environment) {
        this.objectMapper = objectMapper;
        this.serviceName = serviceName;
        this.environment = environment;
    }

    public void logEvent(String level, String eventType, String operation, String outcome,
                         Long latencyMs, String errorClass, String message, Map<String, Object> extraFields) {
        try {
            Map<String, Object> logPayload = new LinkedHashMap<>();
            logPayload.put("timestamp", Instant.now().toString());
            logPayload.put("service", serviceName);
            logPayload.put("environment", environment);
            logPayload.put("level", level);
            logPayload.put("traceId", MDC.get("traceId"));
            logPayload.put("spanId", MDC.get("spanId"));
            logPayload.put("correlationId", MDC.get("correlationId"));
            logPayload.put("organizationId", MDC.get("organizationId"));
            logPayload.put("eventType", eventType);
            logPayload.put("operation", operation);
            logPayload.put("outcome", outcome);
            if (latencyMs != null) {
                logPayload.put("latency", latencyMs);
            }
            if (errorClass != null) {
                logPayload.put("errorClass", errorClass);
            }
            if (message != null) {
                logPayload.put("message", maskSecrets(message));
            }
            if (extraFields != null) {
                extraFields.forEach((k, v) -> {
                    if (v instanceof String s) {
                        logPayload.put(k, maskSecrets(s));
                    } else {
                        logPayload.put(k, v);
                    }
                });
            }

            String json = objectMapper.writeValueAsString(logPayload);
            switch (level.toUpperCase()) {
                case "WARN" -> log.warn("{}", json);
                case "ERROR" -> log.error("{}", json);
                case "DEBUG" -> log.debug("{}", json);
                default -> log.info("{}", json);
            }
        } catch (Exception e) {
            log.info("[{}] event={} op={} outcome={} msg={}", level, eventType, operation, outcome, maskSecrets(message));
        }
    }

    public void info(String eventType, String operation, String outcome, String message) {
        logEvent("INFO", eventType, operation, outcome, null, null, message, null);
    }

    public void info(String eventType, Map<String, Object> details) {
        logEvent("INFO", eventType, "operational", "SUCCESS", null, null, null, details);
    }

    public void warn(String eventType, Map<String, Object> details) {
        logEvent("WARN", eventType, "operational", "WARNING", null, null, null, details);
    }

    public void error(String eventType, String operation, String errorClass, String message, Map<String, Object> details) {
        logEvent("ERROR", eventType, operation, "FAILURE", null, errorClass, message, details);
    }

    public static String maskSecrets(String input) {
        if (input == null || input.isBlank()) return input;
        return SECRET_PATTERN.matcher(input).replaceAll("[REDACTED]");
    }
}
