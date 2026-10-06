package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.service.OrganizationService;
import com.forgeai.identity.domain.model.Organization;
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
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    public ResponseEntity<OrganizationResponse> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Organization org = organizationService.createOrganization(request.name(), request.slug(), request.description(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(org));
    }

    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> getOrganizations(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        List<Organization> orgs = organizationService.getUserOrganizations(userId);
        return ResponseEntity.ok(orgs.stream().map(this::mapToResponse).toList());
    }

    @GetMapping("/{organizationId}")
    public ResponseEntity<OrganizationResponse> getOrganization(
            @PathVariable UUID organizationId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Organization org = organizationService.getOrganization(organizationId, userId);
        return ResponseEntity.ok(mapToResponse(org));
    }

    @PatchMapping("/{organizationId}")
    public ResponseEntity<OrganizationResponse> updateOrganization(
            @PathVariable UUID organizationId,
            @Valid @RequestBody UpdateOrganizationRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Organization org = organizationService.updateOrganization(organizationId, request.name(), request.description(), userId);
        return ResponseEntity.ok(mapToResponse(org));
    }

    @DeleteMapping("/{organizationId}")
    public ResponseEntity<Void> deactivateOrganization(
            @PathVariable UUID organizationId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        organizationService.deactivateOrganization(organizationId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{organizationId}/members")
    public ResponseEntity<List<OrganizationMemberResponse>> getOrganizationMembers(
            @PathVariable UUID organizationId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        List<OrganizationMemberResponse> members = organizationService.getOrganizationMembers(organizationId, userId);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{organizationId}/members")
    public ResponseEntity<OrganizationMemberResponse> addOrganizationMember(
            @PathVariable UUID organizationId,
            @Valid @RequestBody AddMemberRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        OrganizationMembership membership = organizationService.addOrganizationMember(organizationId, userId, request.userId(), request.role());
        
        // Find member from list to get full details (username/email/roleName)
        // In real app, we might return a simpler response or fetch it. For now, doing a small query:
        OrganizationMemberResponse response = organizationService.getOrganizationMembers(organizationId, userId).stream()
                .filter(m -> m.userId().equals(request.userId()))
                .findFirst()
                .orElse(null);
                
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{organizationId}/members/{targetUserId}")
    public ResponseEntity<Void> removeOrganizationMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID targetUserId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        organizationService.removeOrganizationMember(organizationId, userId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{organizationId}/members/{targetUserId}/role")
    public ResponseEntity<OrganizationMemberResponse> changeOrganizationMemberRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID targetUserId,
            @Valid @RequestBody ChangeMemberRoleRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        organizationService.changeOrganizationMemberRole(organizationId, userId, targetUserId, request.role());

        OrganizationMemberResponse response = organizationService.getOrganizationMembers(organizationId, userId).stream()
                .filter(m -> m.userId().equals(targetUserId))
                .findFirst()
                .orElse(null);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{organizationId}/transfer-ownership")
    public ResponseEntity<Void> transferOwnership(
            @PathVariable UUID organizationId,
            @Valid @RequestBody TransferOwnershipRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        organizationService.transferOrganizationOwnership(organizationId, userId, request.newOwnerUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{organizationId}/leave")
    public ResponseEntity<Void> leaveOrganization(
            @PathVariable UUID organizationId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        organizationService.leaveOrganization(organizationId, userId);
        return ResponseEntity.noContent().build();
    }

    private OrganizationResponse mapToResponse(Organization org) {
        return new OrganizationResponse(
                org.getId(),
                org.getName(),
                org.getSlug().value(),
                org.getDescription(),
                org.getStatus().name(),
                org.getCreatedAt(),
                org.getUpdatedAt()
        );
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID)) {
            throw new com.forgeai.identity.domain.exception.InvalidCredentialsException("Authentication required");
        }
        return (UUID) authentication.getPrincipal();
    }
}
