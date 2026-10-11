package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.OrganizationMemberResponse;
import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.*;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import com.forgeai.identity.domain.event.MemberRoleChangedEvent;
import com.forgeai.identity.domain.event.OrganizationCreatedEvent;
import com.forgeai.identity.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final SecurityEventService securityEventService;
    private final OutboxService outboxService;

    @Transactional
    public Organization createOrganization(String name, String slug, String description, UUID creatorId) {
        OrganizationSlug slugObj = new OrganizationSlug(slug);

        if (organizationRepository.findBySlug(slugObj).isPresent()) {
            throw new InvalidOwnershipOperationException("Organization slug already in use");
        }

        Organization org = new Organization();
        org.setName(name);
        org.setSlug(slugObj);
        org.setDescription(description);
        org.setStatus(OrganizationStatus.ACTIVE);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        
        Organization savedOrg = organizationRepository.save(org);

        Role ownerRole = getSystemRole("OWNER");

        OrganizationMembership membership = new OrganizationMembership();
        membership.setUserId(creatorId);
        membership.setOrganizationId(savedOrg.getId());
        membership.setRoleId(ownerRole.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        membershipRepository.save(membership);

        securityEventService.recordEvent(creatorId, savedOrg.getId(), "ORGANIZATION_CREATED", null, null, "{\"message\": \"Organization created\"}");

        if (outboxService != null) {
            outboxService.publish(OrganizationCreatedEvent.builder()
                    .organizationId(savedOrg.getId())
                    .slug(savedOrg.getSlug().value())
                    .name(savedOrg.getName())
                    .build(), null);
        }

        return savedOrg;
    }

    @Transactional(readOnly = true)
    public List<Organization> getUserOrganizations(UUID currentUserId) {
        List<OrganizationMembership> memberships = membershipRepository.findByUserId(currentUserId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .toList();

        return memberships.stream()
                .map(m -> organizationRepository.findById(m.getOrganizationId()).orElse(null))
                .filter(o -> o != null && o.getStatus() == OrganizationStatus.ACTIVE)
                .toList();
    }

    @Transactional(readOnly = true)
    public Organization getOrganization(UUID organizationId, UUID currentUserId) {
        Organization org = findActiveOrganization(organizationId);
        verifyActiveMembership(currentUserId, organizationId);
        return org;
    }

    @Transactional
    public Organization updateOrganization(UUID organizationId, String name, String description, UUID currentUserId) {
        Organization org = findActiveOrganization(organizationId);
        verifyAuthority(currentUserId, organizationId, "OWNER");

        org.setName(name);
        if (description != null) {
            org.setDescription(description);
        }
        org.setUpdatedAt(Instant.now());
        
        Organization saved = organizationRepository.save(org);
        
        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_UPDATED", null, null, "{\"message\": \"Organization updated\"}");
        return saved;
    }

    @Transactional
    public void deactivateOrganization(UUID organizationId, UUID currentUserId) {
        Organization org = findActiveOrganization(organizationId);
        verifyAuthority(currentUserId, organizationId, "OWNER");

        org.setStatus(OrganizationStatus.INACTIVE);
        org.setUpdatedAt(Instant.now());
        organizationRepository.save(org);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_DEACTIVATED", null, null, "{\"message\": \"Organization deactivated\"}");
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> getOrganizationMembers(UUID organizationId, UUID currentUserId) {
        findActiveOrganization(organizationId);
        verifyActiveMembership(currentUserId, organizationId);

        List<OrganizationMembership> memberships = membershipRepository.findByOrganizationId(organizationId);
        List<UUID> userIds = memberships.stream().map(OrganizationMembership::getUserId).toList();
        
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Map<UUID, Role> roles = roleRepository.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));
        
        // Add system roles to the map
        List<Role> systemRoles = List.of(
                getSystemRole("OWNER"), getSystemRole("ADMIN"), getSystemRole("PROJECT_MANAGER"),
                getSystemRole("DEVELOPER"), getSystemRole("TESTER"), getSystemRole("VIEWER"),
                getSystemRole("TEAM_LEAD"), getSystemRole("TEAM_MEMBER")
        );
        for (Role r : systemRoles) {
            roles.putIfAbsent(r.getId(), r);
        }

        return memberships.stream().map(m -> {
            User user = users.get(m.getUserId());
            Role role = roles.get(m.getRoleId());
            return new OrganizationMemberResponse(
                    m.getId(),
                    m.getUserId(),
                    user != null ? user.getUsername().value() : "Unknown",
                    user != null ? user.getEmail().value() : "Unknown",
                    role != null ? role.getName() : "Unknown",
                    m.getStatus().name()
            );
        }).toList();
    }

    @Transactional
    public OrganizationMembership addOrganizationMember(UUID organizationId, UUID currentUserId, UUID targetUserId, String roleName) {
        findActiveOrganization(organizationId);
        verifyAuthority(currentUserId, organizationId, "OWNER");

        if (membershipRepository.findByUserIdAndOrganizationId(targetUserId, organizationId).isPresent()) {
            throw new DuplicateMembershipException("User is already a member of this organization");
        }

        userRepository.findById(targetUserId).orElseThrow(() -> new UserNotFoundException("Target user not found"));

        Role targetRole = getSystemRole(roleName);
        if (targetRole.getScope() != RoleScope.ORGANIZATION) {
            throw new InvalidMembershipRoleException("Role must be organization-scoped");
        }
        if (roleName.equals("OWNER")) {
            throw new InvalidMembershipRoleException("Cannot directly assign OWNER role. Use ownership transfer.");
        }

        OrganizationMembership membership = new OrganizationMembership();
        membership.setUserId(targetUserId);
        membership.setOrganizationId(organizationId);
        membership.setRoleId(targetRole.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        OrganizationMembership saved = membershipRepository.save(membership);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_MEMBER_ADDED", null, null, "{\"targetUserId\": \"" + targetUserId + "\"}");
        return saved;
    }

    @Transactional
    public void removeOrganizationMember(UUID organizationId, UUID currentUserId, UUID targetUserId) {
        organizationRepository.findByIdForUpdate(organizationId).orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
        findActiveOrganization(organizationId);
        verifyAuthority(currentUserId, organizationId, "OWNER");

        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(targetUserId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Membership not found"));

        Role ownerRole = getSystemRole("OWNER");
        if (membership.getRoleId().equals(ownerRole.getId()) && membership.getStatus() == MembershipStatus.ACTIVE) {
            long activeOwnerCount = getActiveOwnerCount(organizationId, ownerRole.getId());
            if (activeOwnerCount <= 1) {
                throw new InvalidOwnershipOperationException("Cannot remove the last active owner of the organization");
            }
        }

        membership.setStatus(MembershipStatus.INACTIVE);
        membership.setUpdatedAt(Instant.now());
        membershipRepository.save(membership);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_MEMBER_REMOVED", null, null, "{\"targetUserId\": \"" + targetUserId + "\"}");
    }

    @Transactional
    public OrganizationMembership changeOrganizationMemberRole(UUID organizationId, UUID currentUserId, UUID targetUserId, String newRoleName) {
        organizationRepository.findByIdForUpdate(organizationId).orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
        findActiveOrganization(organizationId);
        verifyAuthority(currentUserId, organizationId, "OWNER");

        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(targetUserId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Membership not found"));

        Role newRole = getSystemRole(newRoleName);
        if (newRole.getScope() != RoleScope.ORGANIZATION) {
            throw new InvalidMembershipRoleException("Role must be organization-scoped");
        }

        Role ownerRole = getSystemRole("OWNER");
        if (membership.getRoleId().equals(ownerRole.getId()) && !newRole.getId().equals(ownerRole.getId()) && membership.getStatus() == MembershipStatus.ACTIVE) {
            long activeOwnerCount = getActiveOwnerCount(organizationId, ownerRole.getId());
            if (activeOwnerCount <= 1) {
                throw new InvalidOwnershipOperationException("Cannot downgrade the last active owner of the organization");
            }
        }

        membership.setRoleId(newRole.getId());
        membership.setUpdatedAt(Instant.now());
        OrganizationMembership saved = membershipRepository.save(membership);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_MEMBER_ROLE_CHANGED", null, null, "{\"targetUserId\": \"" + targetUserId + "\", \"newRole\": \"" + newRoleName + "\"}");

        if (outboxService != null) {
            outboxService.publish(MemberRoleChangedEvent.builder()
                    .organizationId(organizationId)
                    .userId(targetUserId)
                    .roleName(newRoleName)
                    .status(saved.getStatus() != null ? saved.getStatus().name() : null)
                    .build(), null);
        }

        return saved;
    }

    @Transactional
    public void transferOrganizationOwnership(UUID organizationId, UUID currentOwnerId, UUID newOwnerId) {
        organizationRepository.findByIdForUpdate(organizationId).orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
        findActiveOrganization(organizationId);
        
        OrganizationMembership currentOwnerMembership = membershipRepository.findByUserIdAndOrganizationId(currentOwnerId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Current owner membership not found"));
        if (currentOwnerMembership.getStatus() != MembershipStatus.ACTIVE) {
            throw new OrganizationAccessDeniedException("Current user is not active in this organization");
        }

        Role ownerRole = getSystemRole("OWNER");
        if (!currentOwnerMembership.getRoleId().equals(ownerRole.getId())) {
            throw new InvalidOwnershipOperationException("Current user is not an owner");
        }

        OrganizationMembership newOwnerMembership = membershipRepository.findByUserIdAndOrganizationId(newOwnerId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("New owner membership not found"));
        if (newOwnerMembership.getStatus() != MembershipStatus.ACTIVE) {
            throw new InvalidOwnershipOperationException("New owner is not an active member");
        }

        newOwnerMembership.setRoleId(ownerRole.getId());
        newOwnerMembership.setUpdatedAt(Instant.now());
        membershipRepository.save(newOwnerMembership);

        Role adminRole = getSystemRole("ADMIN");
        currentOwnerMembership.setRoleId(adminRole.getId());
        currentOwnerMembership.setUpdatedAt(Instant.now());
        membershipRepository.save(currentOwnerMembership);

        securityEventService.recordEvent(currentOwnerId, organizationId, "ORGANIZATION_OWNERSHIP_TRANSFERRED", null, null, "{\"newOwnerUserId\": \"" + newOwnerId + "\"}");
    }

    @Transactional
    public void leaveOrganization(UUID organizationId, UUID currentUserId) {
        organizationRepository.findByIdForUpdate(organizationId).orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
        findActiveOrganization(organizationId);

        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(currentUserId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Membership not found"));

        Role ownerRole = getSystemRole("OWNER");
        if (membership.getRoleId().equals(ownerRole.getId()) && membership.getStatus() == MembershipStatus.ACTIVE) {
            long activeOwnerCount = getActiveOwnerCount(organizationId, ownerRole.getId());
            if (activeOwnerCount <= 1) {
                throw new InvalidOwnershipOperationException("Cannot leave as the last active owner of the organization");
            }
        }

        membership.setStatus(MembershipStatus.INACTIVE);
        membership.setUpdatedAt(Instant.now());
        membershipRepository.save(membership);

        securityEventService.recordEvent(currentUserId, organizationId, "ORGANIZATION_MEMBER_LEFT", null, null, "{}");
    }

    private Organization findActiveOrganization(UUID organizationId) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
        if (org.getStatus() != OrganizationStatus.ACTIVE) {
            throw new OrganizationNotFoundException("Organization is not active");
        }
        return org;
    }

    private OrganizationMembership verifyActiveMembership(UUID userId, UUID organizationId) {
        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new OrganizationAccessDeniedException("User is not a member of this organization"));
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new OrganizationAccessDeniedException("Membership is not active");
        }
        return membership;
    }

    private void verifyAuthority(UUID userId, UUID organizationId, String requiredRoleName) {
        OrganizationMembership membership = verifyActiveMembership(userId, organizationId);
        Role role = roleRepository.findById(membership.getRoleId())
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));
        if (!role.getName().equals(requiredRoleName)) {
            throw new OrganizationAccessDeniedException("Insufficient organization permissions");
        }
    }

    private Role getSystemRole(String roleName) {
        return roleRepository.findSystemRoleByName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("System role " + roleName + " not found"));
    }

    private long getActiveOwnerCount(UUID organizationId, UUID ownerRoleId) {
        return membershipRepository.findByOrganizationId(organizationId).stream()
                .filter(m -> m.getRoleId().equals(ownerRoleId) && m.getStatus() == MembershipStatus.ACTIVE)
                .count();
    }
}
