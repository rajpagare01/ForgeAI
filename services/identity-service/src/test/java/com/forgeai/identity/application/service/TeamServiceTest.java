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

    @InjectMocks
    private TeamService teamService;

    private Team activeTeam;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        activeTeam = new Team();
        activeTeam.setId(UUID.randomUUID());
        activeTeam.setOrganizationId(orgId);
        activeTeam.setName("Backend");
    }

    @Test
    void createTeam_Success() {
        when(teamRepository.findByOrganizationIdAndName(orgId, "Frontend")).thenReturn(Optional.empty());
        when(teamRepository.save(any())).thenReturn(activeTeam);

        Team team = teamService.createTeam(orgId, "Frontend", "Desc");
        
        assertNotNull(team);
        verify(teamRepository).save(any(Team.class));
    }

    @Test
    void createTeam_DuplicateNameWithinOrg() {
        when(teamRepository.findByOrganizationIdAndName(orgId, "Backend")).thenReturn(Optional.of(activeTeam));
        
        assertThrows(DuplicateMembershipException.class, () -> 
            teamService.createTeam(orgId, "Backend", "Desc")
        );
    }

    @Test
    void addTeamMember_Success() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        
        when(membershipRepository.findByTeamIdAndUserId(activeTeam.getId(), userId)).thenReturn(Optional.empty());
        
        teamService.addTeamMember(activeTeam.getId(), userId, roleId);
        
        verify(membershipRepository).save(any());
    }
}
