package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.port.in.UserProfileUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Authenticated user profile and account management endpoints.
 *
 * <p>The current user is ALWAYS derived from the Spring Security {@link Authentication}
 * principal set by {@code JwtAuthenticationFilter}. The user ID is never accepted
 * from request bodies or query parameters for /me endpoints.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileUseCase userProfileUseCase;

    /**
     * GET /api/v1/users/me
     * Returns the current user's public profile. Requires authentication.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(userProfileUseCase.getProfile(userId));
    }

    /**
     * PATCH /api/v1/users/me
     * Updates mutable profile fields (firstName, lastName). Requires authentication.
     */
    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(userProfileUseCase.updateProfile(userId, request.firstName(), request.lastName()));
    }

    /**
     * PATCH /api/v1/users/me/username
     * Changes the authenticated user's username. Requires authentication.
     */
    @PatchMapping("/me/username")
    public ResponseEntity<UserProfileResponse> changeUsername(
            @Valid @RequestBody ChangeUsernameRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(userProfileUseCase.changeUsername(userId, request.username()));
    }

    /**
     * POST /api/v1/users/me/email-change
     * Stages an email change (sets pendingEmail). Does NOT verify or swap the email.
     * Requires authentication.
     */
    @PostMapping("/me/email-change")
    public ResponseEntity<UserProfileResponse> requestEmailChange(
            @Valid @RequestBody EmailChangeRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(userProfileUseCase.requestEmailChange(userId, request.newEmail()));
    }

    /**
     * POST /api/v1/users/me/password
     * Changes the authenticated user's password after verifying the current one.
     * Revokes all sessions. Requires authentication.
     */
    @PostMapping("/me/password")
    public ResponseEntity<LogoutResponse> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        userProfileUseCase.changePassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(new LogoutResponse("Password changed. All sessions have been revoked. Please log in again."));
    }

    /**
     * POST /api/v1/users/me/deactivate
     * Self-initiated account deactivation. Revokes all sessions.
     * Requires authentication.
     */
    @PostMapping("/me/deactivate")
    public ResponseEntity<DeactivateResponse> deactivateAccount(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        userProfileUseCase.deactivateAccount(userId);
        return ResponseEntity.ok(new DeactivateResponse("Account has been deactivated. All sessions have been revoked."));
    }

    // -------------------------------------------------------------------------
    // Security: derive user identity strictly from the JWT principal
    // -------------------------------------------------------------------------
    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID)) {
            // Should never reach here because SecurityConfig requires authentication,
            // but guard defensively.
            throw new com.forgeai.identity.domain.exception.InvalidCredentialsException("Authentication required");
        }
        return (UUID) authentication.getPrincipal();
    }
}
