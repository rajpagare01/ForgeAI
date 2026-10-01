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

    @Transactional
    public Role createCustomRole(UUID organizationId, String name, String description, RoleScope scope) {
        Role role = new Role();
        role.setOrganizationId(organizationId);
        role.setName(name);
        role.setDescription(description);
        role.setScope(scope);
        role.setSystemDefined(false);
        role.setPermissions(new HashSet<>());
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());

        return roleRepository.save(role);
    }

    @Transactional
    public Role updateCustomRole(UUID roleId, String name, String description, RoleScope scope) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify system-defined roles");
        }

        role.setName(name);
        role.setDescription(description);
        role.setScope(scope);
        role.setUpdatedAt(Instant.now());

        return roleRepository.save(role);
    }

    @Transactional
    public void deleteCustomRole(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot delete system-defined roles");
        }

        roleRepository.deleteById(roleId);
    }

    @Transactional
    public Role assignPermissionToRole(UUID roleId, String permissionCode) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify permissions of system-defined roles");
        }

        Permission permission = permissionRepository.findByCode(permissionCode)
                .orElseThrow(() -> new PermissionNotFoundException("Permission not found: " + permissionCode));

        if (role.getPermissions() == null) {
            role.setPermissions(new HashSet<>());
        }
        
        role.getPermissions().add(permission);
        role.setUpdatedAt(Instant.now());
        
        return roleRepository.save(role);
    }

    @Transactional
    public Role removePermissionFromRole(UUID roleId, String permissionCode) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (role.isSystemDefined()) {
            throw new InvalidRoleScopeException("Cannot modify permissions of system-defined roles");
        }

        Permission permission = permissionRepository.findByCode(permissionCode)
                .orElseThrow(() -> new PermissionNotFoundException("Permission not found: " + permissionCode));

        if (role.getPermissions() != null) {
            role.getPermissions().removeIf(p -> p.getId().equals(permission.getId()));
        }
        role.setUpdatedAt(Instant.now());
        
        return roleRepository.save(role);
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
