package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.TeamMembershipRepository;
import com.forgeai.identity.domain.model.MembershipStatus;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Permission;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.TeamMembership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final RoleRepository roleRepository;

    @Transactional(readOnly = true)
    public Set<String> getEffectiveOrganizationPermissions(UUID userId, UUID organizationId) {
        Optional<OrganizationMembership> orgMembership = organizationMembershipRepository.findByUserIdAndOrganizationId(userId, organizationId);
        if (orgMembership.isEmpty() || orgMembership.get().getStatus() != MembershipStatus.ACTIVE) {
            return Collections.emptySet();
        }

        UUID roleId = orgMembership.get().getRoleId();
        Optional<Role> role = roleRepository.findById(roleId);
        if (role.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> permissions = new HashSet<>();
        if (role.get().getPermissions() != null) {
            for (Permission p : role.get().getPermissions()) {
                permissions.add(p.getCode());
            }
        }
        return permissions;
    }

    @Transactional(readOnly = true)
    public Set<String> getEffectiveTeamPermissions(UUID userId, UUID organizationId, UUID teamId) {
        // First get base organization permissions
        Set<String> effectivePermissions = new HashSet<>(getEffectiveOrganizationPermissions(userId, organizationId));

        // If not in the organization, they have no access to the team
        if (effectivePermissions.isEmpty()) {
            return Collections.emptySet();
        }

        // Add team-specific permissions
        Optional<TeamMembership> teamMembership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId);
        if (teamMembership.isPresent() && teamMembership.get().getStatus() == MembershipStatus.ACTIVE) {
            UUID roleId = teamMembership.get().getRoleId();
            if (roleId != null) {
                Optional<Role> role = roleRepository.findById(roleId);
                if (role.isPresent() && role.get().getPermissions() != null) {
                    for (Permission p : role.get().getPermissions()) {
                        effectivePermissions.add(p.getCode());
                    }
                }
            }
        }

        return effectivePermissions;
    }

    @Transactional(readOnly = true)
    public boolean hasOrganizationPermission(UUID userId, UUID organizationId, String permissionCode) {
        return getEffectiveOrganizationPermissions(userId, organizationId).contains(permissionCode);
    }

    @Transactional(readOnly = true)
    public boolean hasTeamPermission(UUID userId, UUID organizationId, UUID teamId, String permissionCode) {
        return getEffectiveTeamPermissions(userId, organizationId, teamId).contains(permissionCode);
    }
}
