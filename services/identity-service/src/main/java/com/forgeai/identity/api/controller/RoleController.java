package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.service.RoleService;
import com.forgeai.identity.domain.model.Role;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @PostMapping
    public ResponseEntity<Role> createRole(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateRoleRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Role role = roleService.createCustomRole(organizationId, userId, request.getName(), request.getDescription(), request.getScope());
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    @PatchMapping("/{roleId}")
    public ResponseEntity<Role> updateRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @RequestBody UpdateRoleRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Role role = roleService.updateCustomRole(roleId, userId, organizationId, request.getName(), request.getDescription(), request.getScope());
        return ResponseEntity.ok(role);
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> deleteRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        roleService.deleteCustomRole(roleId, userId, organizationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roleId}/permissions")
    public ResponseEntity<Role> assignPermission(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @Valid @RequestBody AssignPermissionRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Role role = roleService.assignPermissionToRole(roleId, userId, organizationId, request.getPermissionCode());
        return ResponseEntity.ok(role);
    }

    @DeleteMapping("/{roleId}/permissions/{permissionCode}")
    public ResponseEntity<Role> removePermission(
            @PathVariable UUID organizationId,
            @PathVariable UUID roleId,
            @PathVariable String permissionCode,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Role role = roleService.removePermissionFromRole(roleId, userId, organizationId, permissionCode);
        return ResponseEntity.ok(role);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID)) {
            throw new com.forgeai.identity.domain.exception.InvalidCredentialsException("Authentication required");
        }
        return (UUID) authentication.getPrincipal();
    }
}
