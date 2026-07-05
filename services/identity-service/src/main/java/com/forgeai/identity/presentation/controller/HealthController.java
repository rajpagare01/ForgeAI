package com.forgeai.identity.presentation.controller;

import com.forgeai.identity.presentation.response.HealthResponse;
import com.forgeai.identity.presentation.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "System health and version information")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Check API Health", description = "Returns the current health status of the Identity Service")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(new HealthResponse("UP", Instant.now().toString()));
    }

    @GetMapping("/version")
    @Operation(summary = "Get API Version", description = "Returns the current version and build information")
    public ResponseEntity<VersionResponse> getVersion() {
        // In a real scenario, these would be injected via Git properties and Build properties.
        return ResponseEntity.ok(new VersionResponse(
                "identity-service",
                "1.0.0",
                "build-12345",
                "a1b2c3d4",
                System.getProperty("java.version"),
                "3.2.0", // Spring Boot Version mocked
                Instant.now().toString()
        ));
    }
}
