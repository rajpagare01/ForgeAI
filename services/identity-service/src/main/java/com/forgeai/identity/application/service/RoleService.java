package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.PermissionRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.domain.exception.InvalidRoleScopeException;
import com.forgeai.identity.domain.exception.PermissionNotFoundException;
import com.forgeai.identity.domain.exception.RoleNotFoundException;
import com.forgeai.identity.domain.model.Permission;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final SecurityEventService securityEventService;
    private final AuthorizationService authorizationService;

    @Transactional
    public Role createCustomRole(UUID organizationId, UUID currentUserId, String name, String description, RoleScope scope) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ADMINISTER")) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Insufficient permissions to create role");
        }
        if (roleRepository.findByOrganizationIdAndName(organizationId, name).isPresent()) {
            throw new com.forgeai.identity.domain.exception.DuplicateMembershipException("Role name already exists in this organization");
        }

        Role role = new Role();
        role.setOrganizationId(organizationId);
        role.setName(name);
        role.setDescription(description);
        role.setScope(scope);
        role.setSystemDefined(false);
        role.setPermissions(new HashSet<>());
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());

        Role saved = roleRepository.save(role);
        securityEventService.recordEvent(currentUserId, organizationId, "ROLE_CREATED", null, null, "{\"roleId\": \"" + saved.getId() + "\", \"scope\": \"" + scope + "\"}");
        return saved;
    }

    @Transactional
    public Role updateCustomRole(UUID roleId, UUID currentUserId, UUID organizationId, String name, String description, RoleScope scope) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ADMINISTER")) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Insufficient permissions to update role");
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (!role.getOrganizationId().equals(organizationId)) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Role does not belong to this organization");
        }

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify system-defined roles");
        }

        if (!role.getName().equals(name) && roleRepository.findByOrganizationIdAndName(organizationId, name).isPresent()) {
            throw new com.forgeai.identity.domain.exception.DuplicateMembershipException("Role name already exists in this organization");
        }

        role.setName(name);
        role.setDescription(description);
        role.setScope(scope);
        role.setUpdatedAt(Instant.now());

        Role saved = roleRepository.save(role);
        securityEventService.recordEvent(currentUserId, organizationId, "ROLE_UPDATED", null, null, "{\"roleId\": \"" + roleId + "\"}");
        return saved;
    }

    @Transactional
    public void deleteCustomRole(UUID roleId, UUID currentUserId, UUID organizationId) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ADMINISTER")) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Insufficient permissions to delete role");
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (!role.getOrganizationId().equals(organizationId)) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Role does not belong to this organization");
        }

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot delete system-defined roles");
        }

        // We must check if any memberships use this role
        // For simplicity, we could rely on database foreign key constraint to throw an exception,
        // but explicitly checking is better if we have the repositories injected.
        // Wait, RoleRepository currently does not check memberships, but DB has ON DELETE RESTRICT (actually we didn't specify ON DELETE CASCADE for role_id).
        // Let's just catch the DataIntegrityViolationException or let it throw.
        try {
            roleRepository.deleteById(roleId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new InvalidRoleScopeException("Role is currently assigned to active memberships and cannot be deleted");
        }
        
        securityEventService.recordEvent(currentUserId, organizationId, "ROLE_DELETED", null, null, "{\"roleId\": \"" + roleId + "\"}");
    }

    @Transactional
    public Role assignPermissionToRole(UUID roleId, UUID currentUserId, UUID organizationId, String permissionCode) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ADMINISTER") && 
            !authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ASSIGN")) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Insufficient permissions to assign role permissions");
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (!role.getOrganizationId().equals(organizationId)) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Role does not belong to this organization");
        }

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify permissions of system-defined roles");
        }

        Permission permission = permissionRepository.findByCode(permissionCode)
                .orElseThrow(() -> new PermissionNotFoundException("Permission not found: " + permissionCode));

        if (role.getScope() == RoleScope.TEAM) {
            String resource = permission.getResource();
            if (resource != null && (resource.equalsIgnoreCase("ORGANIZATION") || resource.equalsIgnoreCase("ROLE") || resource.equalsIgnoreCase("MEMBER"))) {
                throw new InvalidRoleScopeException("TEAM-scoped role cannot be assigned organization-wide administrative permissions");
            }
        }

        if (role.getPermissions() == null) {
            role.setPermissions(new HashSet<>());
        }
        
        role.getPermissions().add(permission);
        role.setUpdatedAt(Instant.now());
        
        Role saved = roleRepository.save(role);
        securityEventService.recordEvent(currentUserId, organizationId, "ROLE_PERMISSION_ADDED", null, null, "{\"roleId\": \"" + roleId + "\", \"permission\": \"" + permissionCode + "\"}");
        return saved;
    }

    @Transactional
    public Role removePermissionFromRole(UUID roleId, UUID currentUserId, UUID organizationId, String permissionCode) {
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ADMINISTER") && 
            !authorizationService.hasOrganizationPermission(currentUserId, organizationId, "ROLE_ASSIGN")) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Insufficient permissions to remove role permissions");
        }

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (!role.getOrganizationId().equals(organizationId)) {
            throw new com.forgeai.identity.domain.exception.OrganizationAccessDeniedException("Role does not belong to this organization");
        }

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify permissions of system-defined roles");
        }

        Permission permission = permissionRepository.findByCode(permissionCode)
                .orElseThrow(() -> new PermissionNotFoundException("Permission not found: " + permissionCode));

        if (role.getPermissions() != null) {
            role.getPermissions().removeIf(p -> p.getId().equals(permission.getId()));
        }
        role.setUpdatedAt(Instant.now());
        
        Role saved = roleRepository.save(role);
        securityEventService.recordEvent(currentUserId, organizationId, "ROLE_PERMISSION_REMOVED", null, null, "{\"roleId\": \"" + roleId + "\", \"permission\": \"" + permissionCode + "\"}");
        return saved;
    }

    @Transactional(readOnly = true)
    public Set<Permission> resolveEffectivePermissions(Set<UUID> roleIds) {
        Set<Permission> effectivePermissions = new HashSet<>();
        for (UUID roleId : roleIds) {
            roleRepository.findById(roleId).ifPresent(role -> {
                if (role.getPermissions() != null) {
                    effectivePermissions.addAll(role.getPermissions());
                }
            });
        }
        return effectivePermissions;
    }
}
