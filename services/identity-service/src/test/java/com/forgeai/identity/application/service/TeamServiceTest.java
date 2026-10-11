package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.TeamMembershipRepository;
import com.forgeai.identity.application.port.out.TeamRepository;
import com.forgeai.identity.domain.exception.DuplicateMembershipException;
import com.forgeai.identity.domain.model.Team;
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
public class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMembershipRepository membershipRepository;

    @Mock
    private com.forgeai.identity.application.port.out.OrganizationMembershipRepository organizationMembershipRepository;

    @Mock
    private com.forgeai.identity.application.port.out.RoleRepository roleRepository;

    @Mock
    private com.forgeai.identity.application.port.out.UserRepository userRepository;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private TeamService teamService;

    private Team activeTeam;
    private UUID orgId;
    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        currentUserId = UUID.randomUUID();
        activeTeam = new Team();
        activeTeam.setId(UUID.randomUUID());
        activeTeam.setOrganizationId(orgId);
        activeTeam.setName("Backend");
    }

    @Test
    void createTeam_Success() {
        var membership = new com.forgeai.identity.domain.model.OrganizationMembership();
        membership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(currentUserId, orgId)).thenReturn(Optional.of(membership));
        when(authorizationService.hasOrganizationPermission(currentUserId, orgId, "TEAM_CREATE")).thenReturn(true);
        when(teamRepository.findByOrganizationIdAndName(orgId, "Frontend")).thenReturn(Optional.empty());
        when(teamRepository.save(any())).thenReturn(activeTeam);

        Team team = teamService.createTeam(orgId, currentUserId, "Frontend", "Desc");
        
        assertNotNull(team);
        verify(teamRepository).save(any(Team.class));
    }

    @Test
    void createTeam_DuplicateNameWithinOrg() {
        var membership = new com.forgeai.identity.domain.model.OrganizationMembership();
        membership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(currentUserId, orgId)).thenReturn(Optional.of(membership));
        when(authorizationService.hasOrganizationPermission(currentUserId, orgId, "TEAM_CREATE")).thenReturn(true);
        when(teamRepository.findByOrganizationIdAndName(orgId, "Backend")).thenReturn(Optional.of(activeTeam));
        
        assertThrows(DuplicateMembershipException.class, () -> 
            teamService.createTeam(orgId, currentUserId, "Backend", "Desc")
        );
    }

    @Test
    void addTeamMember_Success() {
        UUID targetUserId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        
        var membership = new com.forgeai.identity.domain.model.OrganizationMembership();
        membership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        when(teamRepository.findById(activeTeam.getId())).thenReturn(Optional.of(activeTeam));
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(currentUserId, orgId)).thenReturn(Optional.of(membership));
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(targetUserId, orgId)).thenReturn(Optional.of(membership));
        when(authorizationService.hasOrganizationPermission(currentUserId, orgId, "TEAM_MEMBER_ADD")).thenReturn(true);
        when(membershipRepository.findByTeamIdAndUserId(activeTeam.getId(), targetUserId)).thenReturn(Optional.empty());

        var teamRole = new com.forgeai.identity.domain.model.Role();
        teamRole.setId(roleId);
        teamRole.setScope(com.forgeai.identity.domain.model.RoleScope.TEAM);
        teamRole.setSystemDefined(true);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(teamRole));
        
        teamService.addTeamMember(orgId, activeTeam.getId(), currentUserId, targetUserId, roleId);
        
        verify(membershipRepository).save(any());
    }

    @Test
    void addTeamMember_FailsWhenTargetUserIsNotActiveOrgMember() {
        UUID targetUserId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        var callerMembership = new com.forgeai.identity.domain.model.OrganizationMembership();
        callerMembership.setStatus(com.forgeai.identity.domain.model.MembershipStatus.ACTIVE);
        when(teamRepository.findById(activeTeam.getId())).thenReturn(Optional.of(activeTeam));
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(currentUserId, orgId))
                .thenReturn(Optional.of(callerMembership));
        when(authorizationService.hasOrganizationPermission(currentUserId, orgId, "TEAM_MEMBER_ADD"))
                .thenReturn(true);

        // Target user is NOT in the organization (returns empty)
        when(organizationMembershipRepository.findByUserIdAndOrganizationId(targetUserId, orgId))
                .thenReturn(Optional.empty());

        assertThrows(com.forgeai.identity.domain.exception.OrganizationAccessDeniedException.class, () ->
                teamService.addTeamMember(orgId, activeTeam.getId(), currentUserId, targetUserId, roleId)
        );

        verify(membershipRepository, never()).save(any());
    }
}

