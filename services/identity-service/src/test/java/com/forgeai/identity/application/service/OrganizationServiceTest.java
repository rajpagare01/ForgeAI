package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidOwnershipOperationException;
import com.forgeai.identity.domain.exception.OrganizationAccessDeniedException;
import com.forgeai.identity.domain.model.MembershipStatus;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.OrganizationStatus;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

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
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private SecurityEventService securityEventService;

    @InjectMocks
    private OrganizationService organizationService;

    private Organization activeOrg;
    private Role ownerRole;
    private Role memberRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        activeOrg = new Organization();
        activeOrg.setId(UUID.randomUUID());
        activeOrg.setSlug(new OrganizationSlug("test-org"));
        activeOrg.setStatus(OrganizationStatus.ACTIVE);

        ownerRole = new Role();
        ownerRole.setId(UUID.randomUUID());
        ownerRole.setName("OWNER");
        ownerRole.setScope(RoleScope.ORGANIZATION);
        
        memberRole = new Role();
        memberRole.setId(UUID.randomUUID());
        memberRole.setName("MEMBER");
        memberRole.setScope(RoleScope.ORGANIZATION);
        
        adminRole = new Role();
        adminRole.setId(UUID.randomUUID());
        adminRole.setName("ADMIN");
        adminRole.setScope(RoleScope.ORGANIZATION);
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
        membership.setStatus(MembershipStatus.ACTIVE);
        
        when(organizationRepository.findByIdForUpdate(activeOrg.getId())).thenReturn(Optional.of(activeOrg));
        when(organizationRepository.findById(activeOrg.getId())).thenReturn(Optional.of(activeOrg));
        
        when(membershipRepository.findByUserIdAndOrganizationId(userId, activeOrg.getId()))
            .thenReturn(Optional.of(membership));
            
        when(roleRepository.findById(ownerRole.getId())).thenReturn(Optional.of(ownerRole));
        when(roleRepository.findSystemRoleByName("OWNER")).thenReturn(Optional.of(ownerRole));
        
        when(membershipRepository.findByOrganizationId(activeOrg.getId())).thenReturn(List.of(membership));
            
        assertThrows(InvalidOwnershipOperationException.class, () -> 
            organizationService.removeOrganizationMember(activeOrg.getId(), userId, userId)
        );
    }

    @Test
    void transferOwnership_Success() {
        UUID currentOwnerId = UUID.randomUUID();
        UUID newOwnerId = UUID.randomUUID();
        
        OrganizationMembership currentOwnerMembership = new OrganizationMembership();
        currentOwnerMembership.setRoleId(ownerRole.getId());
        currentOwnerMembership.setStatus(MembershipStatus.ACTIVE);
        
        OrganizationMembership newOwnerMembership = new OrganizationMembership();
        newOwnerMembership.setRoleId(memberRole.getId());
        newOwnerMembership.setStatus(MembershipStatus.ACTIVE);
        
        when(organizationRepository.findByIdForUpdate(activeOrg.getId())).thenReturn(Optional.of(activeOrg));
        when(organizationRepository.findById(activeOrg.getId())).thenReturn(Optional.of(activeOrg));
        
        when(roleRepository.findSystemRoleByName("OWNER")).thenReturn(Optional.of(ownerRole));
        when(roleRepository.findSystemRoleByName("ADMIN")).thenReturn(Optional.of(adminRole));
        
        when(membershipRepository.findByUserIdAndOrganizationId(currentOwnerId, activeOrg.getId()))
            .thenReturn(Optional.of(currentOwnerMembership));
        when(membershipRepository.findByUserIdAndOrganizationId(newOwnerId, activeOrg.getId()))
            .thenReturn(Optional.of(newOwnerMembership));
            
        organizationService.transferOrganizationOwnership(activeOrg.getId(), currentOwnerId, newOwnerId);
        
        verify(membershipRepository, times(2)).save(any());
        assertEquals(adminRole.getId(), currentOwnerMembership.getRoleId());
        assertEquals(ownerRole.getId(), newOwnerMembership.getRoleId());
    }
}
