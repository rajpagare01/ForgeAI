package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.AcceptInvitationRequest;
import com.forgeai.identity.api.dto.CreateInvitationRequest;
import com.forgeai.identity.api.dto.InvitationResponse;
import com.forgeai.identity.application.service.InvitationService;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.model.OrganizationMembership;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    /**
     * POST /api/v1/organizations/{orgId}/invitations
     * Requires MEMBER_INVITE permission. Creates an invitation with a hashed token, target role, and 7-day expiration.
     */
    @PostMapping("/organizations/{orgId}/invitations")
    public ResponseEntity<InvitationResponse> createInvitation(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateInvitationRequest request,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        InvitationResponse response = invitationService.createInvitation(orgId, currentUserId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * GET /api/v1/organizations/{orgId}/invitations
     */
    @GetMapping("/organizations/{orgId}/invitations")
    public ResponseEntity<List<InvitationResponse>> getInvitations(
            @PathVariable UUID orgId,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        return ResponseEntity.ok(invitationService.getOrganizationInvitations(orgId, currentUserId));
    }

    /**
     * DELETE /api/v1/organizations/{orgId}/invitations/{invitationId}
     * Cancels a pending invitation.
     */
    @DeleteMapping("/organizations/{orgId}/invitations/{invitationId}")
    public ResponseEntity<Void> cancelInvitation(
            @PathVariable UUID orgId,
            @PathVariable UUID invitationId,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        invitationService.cancelInvitation(orgId, invitationId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/invitations/accept
     * Validates token hash and expiration. Atomically inserts the user into organization_memberships
     * with the assigned role, marks the invitation as ACCEPTED, and emits MemberRoleChangedEvent via outbox.
     */
    @PostMapping("/invitations/accept")
    public ResponseEntity<OrganizationMembership> acceptInvitation(
            @Valid @RequestBody AcceptInvitationRequest request,
            Authentication authentication) {
        UUID currentUserId = extractUserId(authentication);
        OrganizationMembership membership = invitationService.acceptInvitation(request.token(), currentUserId);
        return ResponseEntity.ok(membership);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID)) {
            throw new InvalidCredentialsException("Authentication required");
        }
        return (UUID) authentication.getPrincipal();
    }
}
