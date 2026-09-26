package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.TeamMembershipRepository;
import com.forgeai.identity.application.port.out.TeamRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.Team;
import com.forgeai.identity.domain.model.TeamMembership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository membershipRepository;

    @Transactional
    public Team createTeam(UUID organizationId, String name, String description) {
        if (teamRepository.findByOrganizationIdAndName(organizationId, name).isPresent()) {
            throw new DuplicateMembershipException("Team name must be unique within the organization");
        }

        Team team = new Team();
        team.setOrganizationId(organizationId);
        team.setName(name);
        team.setDescription(description);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());

        return teamRepository.save(team);
    }

    @Transactional(readOnly = true)
    public Team getTeam(UUID id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new TeamNotFoundException("Team not found"));
    }

    @Transactional
    public Team updateTeam(UUID id, String name, String description) {
        Team team = getTeam(id);

        if (!team.getName().equals(name)) {
            if (teamRepository.findByOrganizationIdAndName(team.getOrganizationId(), name).isPresent()) {
                throw new DuplicateMembershipException("Team name must be unique within the organization");
            }
            team.setName(name);
        }

        team.setDescription(description);
        team.setUpdatedAt(Instant.now());
        return teamRepository.save(team);
    }

    @Transactional
    public void deleteTeam(UUID id) {
        // Find team to ensure it exists
        getTeam(id);
        teamRepository.deleteById(id);
    }

    @Transactional
    public TeamMembership addTeamMember(UUID teamId, UUID userId, UUID roleId) {
        if (membershipRepository.findByTeamIdAndUserId(teamId, userId).isPresent()) {
            throw new DuplicateMembershipException("User is already a member of this team");
        }

        TeamMembership membership = new TeamMembership();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRoleId(roleId);
        membership.setStatus("ACTIVE");
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        return membershipRepository.save(membership);
    }

    @Transactional
    public void removeTeamMember(UUID teamId, UUID userId) {
        TeamMembership membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException("Team membership not found"));
        
        membershipRepository.deleteById(membership.getId());
    }

    @Transactional
    public TeamMembership changeTeamMemberRole(UUID teamId, UUID userId, UUID newRoleId) {
        TeamMembership membership = membershipRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new MembershipNotFoundException("Team membership not found"));

        membership.setRoleId(newRoleId);
        membership.setUpdatedAt(Instant.now());
        return membershipRepository.save(membership);
    }
}
