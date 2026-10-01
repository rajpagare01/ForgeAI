package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.OrganizationStatus;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;

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

        // Find system owner role
        Role ownerRole = roleRepository.findSystemRoleByName("OWNER")
                .orElseThrow(() -> new RoleNotFoundException("System OWNER role not found"));

        OrganizationMembership membership = new OrganizationMembership();
        membership.setUserId(creatorId);
        membership.setOrganizationId(savedOrg.getId());
        membership.setRoleId(ownerRole.getId());
        membership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        membershipRepository.save(membership);

        return savedOrg;
    }

    @Transactional(readOnly = true)
    public Organization getOrganization(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new OrganizationNotFoundException("Organization not found"));
    }

    @Transactional
    public Organization updateOrganization(UUID id, String name, String description) {
        Organization org = getOrganization(id);
        org.setName(name);
        org.setDescription(description);
        org.setUpdatedAt(Instant.now());
        return organizationRepository.save(org);
    }

    @Transactional
    public OrganizationMembership addOrganizationMember(UUID organizationId, UUID userId, UUID roleId) {
        if (membershipRepository.findByUserIdAndOrganizationId(userId, organizationId).isPresent()) {
            throw new DuplicateMembershipException("User is already a member of this organization");
        }

        OrganizationMembership membership = new OrganizationMembership();
        membership.setUserId(userId);
        membership.setOrganizationId(organizationId);
        membership.setRoleId(roleId);
        membership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        return membershipRepository.save(membership);
    }

    @Transactional
    public void removeOrganizationMember(UUID organizationId, UUID userId) {
        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Membership not found"));

        Role ownerRole = roleRepository.findSystemRoleByName("OWNER")
                .orElseThrow(() -> new RoleNotFoundException("System OWNER role not found"));

        if (membership.getRoleId().equals(ownerRole.getId())) {
            long ownerCount = membershipRepository.countByOrganizationIdAndRoleId(organizationId, ownerRole.getId());
            if (ownerCount <= 1) {
                throw new InvalidOwnershipOperationException("Cannot remove the last owner of the organization");
            }
        }

        membershipRepository.deleteById(membership.getId());
    }

    @Transactional
    public OrganizationMembership changeOrganizationMemberRole(UUID organizationId, UUID userId, UUID newRoleId) {
        OrganizationMembership membership = membershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Membership not found"));

        Role ownerRole = roleRepository.findSystemRoleByName("OWNER")
                .orElseThrow(() -> new RoleNotFoundException("System OWNER role not found"));

        if (membership.getRoleId().equals(ownerRole.getId()) && !newRoleId.equals(ownerRole.getId())) {
            long ownerCount = membershipRepository.countByOrganizationIdAndRoleId(organizationId, ownerRole.getId());
            if (ownerCount <= 1) {
                throw new InvalidOwnershipOperationException("Cannot downgrade the last owner of the organization");
            }
        }

        membership.setRoleId(newRoleId);
        membership.setUpdatedAt(Instant.now());
        return membershipRepository.save(membership);
    }

    @Transactional
    public void transferOrganizationOwnership(UUID organizationId, UUID currentOwnerId, UUID newOwnerId) {
        Role ownerRole = roleRepository.findSystemRoleByName("OWNER")
                .orElseThrow(() -> new RoleNotFoundException("System OWNER role not found"));

        OrganizationMembership currentOwnerMembership = membershipRepository.findByUserIdAndOrganizationId(currentOwnerId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("Current owner membership not found"));

        if (!currentOwnerMembership.getRoleId().equals(ownerRole.getId())) {
            throw new InvalidOwnershipOperationException("Current user is not an owner");
        }

        OrganizationMembership newOwnerMembership = membershipRepository.findByUserIdAndOrganizationId(newOwnerId, organizationId)
                .orElseThrow(() -> new MembershipNotFoundException("New owner membership not found"));

        // Make the new user an owner
        newOwnerMembership.setRoleId(ownerRole.getId());
        newOwnerMembership.setUpdatedAt(Instant.now());
        membershipRepository.save(newOwnerMembership);

        // Downgrade the current owner to MEMBER (assuming a MEMBER role exists, otherwise just keep as is, but ownership transfer implies downgrade)
        Role memberRole = roleRepository.findSystemRoleByName("MEMBER")
                .orElseThrow(() -> new RoleNotFoundException("System MEMBER role not found"));
        currentOwnerMembership.setRoleId(memberRole.getId());
        currentOwnerMembership.setUpdatedAt(Instant.now());
        membershipRepository.save(currentOwnerMembership);
    }
}
