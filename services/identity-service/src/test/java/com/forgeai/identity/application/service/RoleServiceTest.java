package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.PermissionRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.domain.exception.InvalidRoleScopeException;
import com.forgeai.identity.domain.model.Permission;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private RoleService roleService;

    private Role systemRole;
    private Role customRole;
    private Permission permission;

    @BeforeEach
    void setUp() {
        systemRole = new Role();
        systemRole.setId(UUID.randomUUID());
        systemRole.setSystemDefined(true);
        systemRole.setName("OWNER");
        
        customRole = new Role();
        customRole.setId(UUID.randomUUID());
        customRole.setSystemDefined(false);
        customRole.setPermissions(new HashSet<>());
        
        permission = new Permission();
        permission.setId(UUID.randomUUID());
        permission.setCode("org:read");
    }

    @Test
    void createCustomRole_Success() {
        when(authorizationService.hasOrganizationPermission(any(), any(), eq("ROLE_ADMINISTER"))).thenReturn(true);
        when(roleRepository.save(any())).thenReturn(customRole);
        
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Role role = roleService.createCustomRole(orgId, userId, "Custom", "desc", RoleScope.ORGANIZATION);
        
        assertNotNull(role);
        verify(roleRepository).save(any());
    }

    @Test
    void preventModificationOfSystemRole() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        systemRole.setOrganizationId(orgId);
        when(authorizationService.hasOrganizationPermission(any(), any(), eq("ROLE_ADMINISTER"))).thenReturn(true);
        when(authorizationService.hasOrganizationPermission(any(), any(), eq("ROLE_ASSIGN"))).thenReturn(true);
        when(roleRepository.findById(systemRole.getId())).thenReturn(Optional.of(systemRole));
        
        assertThrows(InvalidRoleScopeException.class, () -> 
            roleService.updateCustomRole(systemRole.getId(), userId, orgId, "New", "desc", RoleScope.ORGANIZATION)
        );
        
        assertThrows(InvalidRoleScopeException.class, () -> 
            roleService.assignPermissionToRole(systemRole.getId(), userId, orgId, "org:read")
        );
    }

    @Test
    void assignPermission_Success() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        customRole.setOrganizationId(orgId);
        when(authorizationService.hasOrganizationPermission(any(), any(), eq("ROLE_ADMINISTER"))).thenReturn(true);
        when(roleRepository.findById(customRole.getId())).thenReturn(Optional.of(customRole));
        when(permissionRepository.findByCode("org:read")).thenReturn(Optional.of(permission));
        when(roleRepository.save(any())).thenReturn(customRole);
        
        roleService.assignPermissionToRole(customRole.getId(), userId, orgId, "org:read");
        
        assertTrue(customRole.getPermissions().contains(permission));
        verify(roleRepository).save(customRole);
    }
}
