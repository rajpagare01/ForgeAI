package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.GitHubAppConfigResponse;
import com.forgeai.identity.api.dto.GitHubAuthStartResponse;
import com.forgeai.identity.api.dto.GitHubCallbackRequest;
import com.forgeai.identity.api.dto.GitHubIdentityDto;
import com.forgeai.identity.api.dto.GitHubStatusResponse;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.in.GitHubAuthenticationUseCase;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.infrastructure.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth/github")
public class GitHubAuthController {

    private final GitHubAuthenticationUseCase gitHubAuthenticationUseCase;
    private final JwtService jwtService;

    @Autowired
    public GitHubAuthController(
            GitHubAuthenticationUseCase gitHubAuthenticationUseCase,
            @Autowired(required = false) JwtService jwtService) {
        this.gitHubAuthenticationUseCase = gitHubAuthenticationUseCase;
        this.jwtService = jwtService;
    }

    /**
     * GET /api/v1/auth/github/config
     * Returns whether GitHub auth is configured and the public client ID.
     */
    @GetMapping("/config")
    public ResponseEntity<GitHubAppConfigResponse> getConfig() {
        return ResponseEntity.ok(gitHubAuthenticationUseCase.getConfig());
    }

    /**
     * GET /api/v1/auth/github/start
     * Generates a cryptographically secure anti-CSRF state and returns the GitHub authorize URL.
     * Can optionally bind state to the current authenticated user for account linking.
     */
    @GetMapping("/start")
    public ResponseEntity<GitHubAuthStartResponse> start(
            @RequestParam(value = "redirectUri", required = false) String redirectUri,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation,
            Authentication authentication) {

        UUID userId = resolveOptionalUserId(authentication, authHeader, headerUserId, internalAttestation);
        GitHubAuthStartResponse response = gitHubAuthenticationUseCase.startAuthorization(userId, redirectUri);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/github/callback
     * Handles the OAuth callback from the frontend: validates state, exchanges code for token,
     * resolves GitHub profile, provisions personal workspace if new user, and issues ForgeAI JWT.
     */
    @PostMapping("/callback")
    public ResponseEntity<LoginResponse> callback(@Valid @RequestBody GitHubCallbackRequest request) {
        LoginResponse response = gitHubAuthenticationUseCase.handleCallback(
                request.code(),
                request.state(),
                request.redirectUri()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/github/link
     * Links a GitHub account to the currently authenticated ForgeAI user.
     */
    @PostMapping("/link")
    public ResponseEntity<GitHubIdentityDto> link(
            @Valid @RequestBody GitHubCallbackRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation,
            Authentication authentication) {

        UUID userId = requireUserId(authentication, authHeader, headerUserId, internalAttestation);
        GitHubIdentityDto response = gitHubAuthenticationUseCase.linkAccount(
                userId,
                request.code(),
                request.state(),
                request.redirectUri()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/github/disconnect
     * Unlinks the GitHub account from the currently authenticated user, with lockout protection.
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, String>> disconnect(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation,
            Authentication authentication) {

        UUID userId = requireUserId(authentication, authHeader, headerUserId, internalAttestation);
        gitHubAuthenticationUseCase.disconnectAccount(userId);
        return ResponseEntity.ok(Map.of("message", "GitHub account unlinked successfully"));
    }

    /**
     * GET /api/v1/auth/github/status
     * Returns whether the current user has a linked GitHub account and public profile info.
     */
    @GetMapping("/status")
    public ResponseEntity<GitHubStatusResponse> getStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation,
            Authentication authentication) {

        UUID userId = requireUserId(authentication, authHeader, headerUserId, internalAttestation);
        return ResponseEntity.ok(gitHubAuthenticationUseCase.getStatus(userId));
    }

    private UUID requireUserId(Authentication authentication, String authHeader, String headerUserId, String internalAttestation) {
        UUID userId = resolveOptionalUserId(authentication, authHeader, headerUserId, internalAttestation);
        if (userId == null) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return userId;
    }

    private UUID resolveOptionalUserId(Authentication authentication, String authHeader, String headerUserId, String internalAttestation) {
        // 1. Only trust X-User-Id if attested by internal gateway marker
        if ("attested".equalsIgnoreCase(internalAttestation) && headerUserId != null && !headerUserId.isBlank()) {
            try {
                return UUID.fromString(headerUserId.trim());
            } catch (IllegalArgumentException ignored) {}
        }

        // 2. Spring Security Authentication context
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UUID uuid) {
                return uuid;
            }
            if (principal instanceof String str && !str.equalsIgnoreCase("anonymousUser")) {
                try {
                    return UUID.fromString(str);
                } catch (IllegalArgumentException ignored) {}
            }
            if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                try {
                    return UUID.fromString(jwt.getSubject());
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // 3. Fallback: Parse Bearer token directly if available
        if (authHeader != null && authHeader.startsWith("Bearer ") && jwtService != null) {
            try {
                String token = authHeader.substring(7).trim();
                return jwtService.extractUserId(token);
            } catch (Exception ignored) {}
        }

        return null;
    }
}
