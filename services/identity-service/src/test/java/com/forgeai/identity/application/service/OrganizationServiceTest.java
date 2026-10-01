package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.domain.exception.InvalidOwnershipOperationException;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMembershipRepository membershipRepository;

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private OrganizationService organizationService;

    private Organization activeOrg;
    private Role ownerRole;
    private Role memberRole;

    @BeforeEach
    void setUp() {
        activeOrg = new Organization();
        activeOrg.setId(UUID.randomUUID());
        activeOrg.setSlug(new OrganizationSlug("test-org"));

        ownerRole = new Role();
        ownerRole.setId(UUID.randomUUID());
        ownerRole.setName("OWNER");
        
        memberRole = new Role();
        memberRole.setId(UUID.randomUUID());
        memberRole.setName("MEMBER");
    }

    @Test
    void createOrganization_Success() {
        when(organizationRepository.findBySlug(any())).thenReturn(Optional.empty());
        when(organizationRepository.save(any())).thenReturn(activeOrg);
        when(roleRepository.findSystemRoleByName("OWNER")).thenReturn(Optional.of(ownerRole));

        Organization org = organizationService.createOrganization("Test Org", "test-org", "desc", UUID.randomUUID());
        
        assertNotNull(org);
        verify(organizationRepository).save(any());
        verify(membershipRepository).save(any(OrganizationMembership.class));
    }

    @Test
    void preventRemovingLastOwner() {
        UUID userId = UUID.randomUUID();
        OrganizationMembership membership = new OrganizationMembership();
        membership.setRoleId(ownerRole.getId());
        
        when(membershipRepository.findByUserIdAndOrganizationId(userId, activeOrg.getId()))
            .thenReturn(Optional.of(membership));
        when(roleRepository.findSystemRoleByName("OWNER")).thenReturn(Optional.of(ownerRole));
        when(membershipRepository.countByOrganizationIdAndRoleId(activeOrg.getId(), ownerRole.getId()))
            .thenReturn(1L); // Only 1 owner
            
        assertThrows(InvalidOwnershipOperationException.class, () -> 
            organizationService.removeOrganizationMember(activeOrg.getId(), userId)
        );
        
        verify(membershipRepository, never()).deleteById(any());
    }

    @Test
    void transferOwnership_Success() {
        UUID currentOwnerId = UUID.randomUUID();
        UUID newOwnerId = UUID.randomUUID();
        
        OrganizationMembership currentOwnerMembership = new OrganizationMembership();
        currentOwnerMembership.setRoleId(ownerRole.getId());
        
        OrganizationMembership newOwnerMembership = new OrganizationMembership();
        newOwnerMembership.setRoleId(memberRole.getId());
        
        when(roleRepository.findSystemRoleByName("OWNER")).thenReturn(Optional.of(ownerRole));
        when(roleRepository.findSystemRoleByName("MEMBER")).thenReturn(Optional.of(memberRole));
        
        when(membershipRepository.findByUserIdAndOrganizationId(currentOwnerId, activeOrg.getId()))
            .thenReturn(Optional.of(currentOwnerMembership));
        when(membershipRepository.findByUserIdAndOrganizationId(newOwnerId, activeOrg.getId()))
            .thenReturn(Optional.of(newOwnerMembership));
            
        organizationService.transferOrganizationOwnership(activeOrg.getId(), currentOwnerId, newOwnerId);
        
        verify(membershipRepository, times(2)).save(any());
        assertEquals(memberRole.getId(), currentOwnerMembership.getRoleId());
        assertEquals(ownerRole.getId(), newOwnerMembership.getRoleId());
    }
}
