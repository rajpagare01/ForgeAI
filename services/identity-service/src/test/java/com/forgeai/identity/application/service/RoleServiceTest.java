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
        when(roleRepository.save(any())).thenReturn(customRole);
        
        Role role = roleService.createCustomRole(UUID.randomUUID(), "Custom", "desc", RoleScope.ORGANIZATION);
        
        assertNotNull(role);
        verify(roleRepository).save(any());
    }

    @Test
    void preventModificationOfSystemRole() {
        when(roleRepository.findById(systemRole.getId())).thenReturn(Optional.of(systemRole));
        
        assertThrows(InvalidRoleScopeException.class, () -> 
            roleService.updateCustomRole(systemRole.getId(), "New", "desc", RoleScope.ORGANIZATION)
        );
        
        assertThrows(InvalidRoleScopeException.class, () -> 
            roleService.assignPermissionToRole(systemRole.getId(), "org:read")
        );
    }

    @Test
    void assignPermission_Success() {
        when(roleRepository.findById(customRole.getId())).thenReturn(Optional.of(customRole));
        when(permissionRepository.findByCode("org:read")).thenReturn(Optional.of(permission));
        when(roleRepository.save(any())).thenReturn(customRole);
        
        roleService.assignPermissionToRole(customRole.getId(), "org:read");
        
        assertTrue(customRole.getPermissions().contains(permission));
        verify(roleRepository).save(customRole);
    }
}
