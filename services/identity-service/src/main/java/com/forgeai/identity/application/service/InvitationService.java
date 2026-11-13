package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.CreateInvitationRequest;
import com.forgeai.identity.api.dto.InvitationResponse;
import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.event.MemberRoleChangedEvent;
import com.forgeai.identity.domain.exception.DuplicateMembershipException;
import com.forgeai.identity.domain.exception.InvalidMembershipRoleException;
import com.forgeai.identity.domain.exception.OrganizationAccessDeniedException;
import com.forgeai.identity.domain.exception.OrganizationNotFoundException;
import com.forgeai.identity.domain.exception.ResourceNotFoundException;
import com.forgeai.identity.domain.exception.RoleNotFoundException;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.model.InvitationStatus;
import com.forgeai.identity.domain.model.MembershipStatus;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.InvitationJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.InvitationRepository;
import com.forgeai.identity.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final SecurityEventService securityEventService;
    private final OutboxService outboxService;

    @Transactional
    public InvitationResponse createInvitation(UUID organizationId, UUID currentUserId, CreateInvitationRequest request) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));

        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "MEMBER_INVITE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to invite members");
        }

        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (role.getScope() != RoleScope.ORGANIZATION) {
            throw new InvalidMembershipRoleException("Role must be organization-scoped");
        }
        if ("OWNER".equalsIgnoreCase(role.getName())) {
            throw new InvalidMembershipRoleException("Cannot invite users with OWNER role");
        }

        // Check if user is already an active member if user exists
        userRepository.findByEmail(new Email(request.email())).ifPresent(existingUser -> {
            membershipRepository.findByUserIdAndOrganizationId(existingUser.getId(), organizationId)
                    .ifPresent(m -> {
                        if (m.getStatus() == MembershipStatus.ACTIVE) {
                            throw new DuplicateMembershipException("User is already a member of this organization");
                        }
                    });
        });

        String rawToken = generateSecureToken(64);
        String tokenHash = hashSha256(rawToken);

        InvitationJpaEntity entity = new InvitationJpaEntity();
        entity.setOrganizationId(organizationId);
        entity.setEmail(request.email().trim().toLowerCase());
        entity.setRoleId(request.roleId());
        entity.setInvitedBy(currentUserId);
        entity.setToken(tokenHash);
        entity.setStatus(InvitationStatus.PENDING.name());
        entity.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        InvitationJpaEntity saved = invitationRepository.save(entity);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_INVITATION_CREATED", null, null,
                "{\"email\":\"" + saved.getEmail() + "\",\"roleId\":\"" + saved.getRoleId() + "\"}");

        // Return response with rawToken so inviter/system can dispatch email invitation
        return toResponse(saved, rawToken);
    }

    public static class InvitationExpiredException extends IllegalArgumentException {
        public InvitationExpiredException(String message) {
            super(message);
        }
    }

    @Transactional(noRollbackFor = {InvitationExpiredException.class})
    public OrganizationMembership acceptInvitation(String rawToken, UUID authenticatedUserId) {
        String tokenHash = hashSha256(rawToken);

        // Find invitation by token hash (or raw token if hashed token was matched)
        InvitationJpaEntity invitation = invitationRepository.findByToken(tokenHash)
                .or(() -> invitationRepository.findByToken(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired invitation token"));

        if (!InvitationStatus.PENDING.name().equalsIgnoreCase(invitation.getStatus())) {
            throw new IllegalArgumentException("Invitation is no longer pending");
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED.name());
            invitation.setUpdatedAt(Instant.now());
            invitationRepository.save(invitation);
            throw new InvitationExpiredException("Invitation token has expired");
        }

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (invitation.getEmail() != null && !user.getEmail().value().equalsIgnoreCase(invitation.getEmail().trim())) {
            throw new com.forgeai.identity.domain.exception.AccessDeniedException("Invitation email does not match authenticated user");
        }

        Role role = roleRepository.findById(invitation.getRoleId())
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        // Check if user already holds membership
        var existingMembership = membershipRepository.findByUserIdAndOrganizationId(user.getId(), invitation.getOrganizationId());
        OrganizationMembership membership;
        if (existingMembership.isPresent()) {
            membership = existingMembership.get();
            boolean isExistingOwner = membership.getRoleId() != null && roleRepository.findById(membership.getRoleId())
                    .map(r -> "OWNER".equalsIgnoreCase(r.getName()))
                    .orElse(false);
            if (isExistingOwner && !role.getId().equals(membership.getRoleId())) {
                throw new com.forgeai.identity.domain.exception.InvalidOwnershipOperationException(
                        "Cannot overwrite an existing OWNER membership role via invitation");
            }
            membership.setRoleId(role.getId());
            membership.setStatus(MembershipStatus.ACTIVE);
            membership.setUpdatedAt(Instant.now());
        } else {
            membership = new OrganizationMembership();
            membership.setUserId(user.getId());
            membership.setOrganizationId(invitation.getOrganizationId());
            membership.setRoleId(role.getId());
            membership.setStatus(MembershipStatus.ACTIVE);
            membership.setCreatedAt(Instant.now());
            membership.setUpdatedAt(Instant.now());
        }

        OrganizationMembership savedMembership = membershipRepository.save(membership);

        // Mark invitation ACCEPTED
        invitation.setStatus(InvitationStatus.ACCEPTED.name());
        invitation.setUpdatedAt(Instant.now());
        invitationRepository.save(invitation);

        // Emit outbox event
        if (outboxService != null) {
            outboxService.publish(MemberRoleChangedEvent.builder()
                    .organizationId(invitation.getOrganizationId())
                    .userId(user.getId())
                    .roleName(role.getName())
                    .status(MembershipStatus.ACTIVE.name())
                    .build(), null);
        }

        securityEventService.recordEvent(user.getId(), invitation.getOrganizationId(), "ORGANIZATION_INVITATION_ACCEPTED", null, null,
                "{\"invitationId\":\"" + invitation.getId() + "\"}");

        return savedMembership;
    }

    @Transactional
    public void cancelInvitation(UUID organizationId, UUID invitationId, UUID currentUserId) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "MEMBER_INVITE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to cancel invitations");
        }

        InvitationJpaEntity invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (!invitation.getOrganizationId().equals(organizationId)) {
            throw new OrganizationAccessDeniedException("Invitation does not belong to this organization");
        }

        invitation.setStatus(InvitationStatus.CANCELLED.name());
        invitation.setUpdatedAt(Instant.now());
        invitationRepository.save(invitation);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_INVITATION_CANCELLED", null, null,
                "{\"invitationId\":\"" + invitationId + "\"}");
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> getOrganizationInvitations(UUID organizationId, UUID currentUserId) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "MEMBER_INVITE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to view invitations");
        }

        return invitationRepository.findByOrganizationId(organizationId).stream()
                .map(entity -> toResponse(entity, null))
                .toList();
    }

    private InvitationResponse toResponse(InvitationJpaEntity entity, String rawToken) {
        return new InvitationResponse(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getEmail(),
                entity.getRoleId(),
                entity.getInvitedBy(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                rawToken
        );
    }

    private String generateSecureToken(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String hashSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing error", e);
        }
    }
}
