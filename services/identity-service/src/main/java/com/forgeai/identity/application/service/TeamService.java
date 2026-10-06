package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.TeamMembershipRepository;
import com.forgeai.identity.application.port.out.TeamRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.MembershipStatus;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import com.forgeai.identity.domain.model.Team;
import com.forgeai.identity.domain.model.TeamMembership;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final SecurityEventService securityEventService;
    private final AuthorizationService authorizationService;

    @Transactional
    public Team createTeam(UUID organizationId, UUID currentUserId, String name, String description) {
        verifyActiveOrganizationMembership(currentUserId, organizationId);
        
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_CREATE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to create team");
        }

        if (teamRepository.findByOrganizationIdAndName(organizationId, name).isPresent()) {
            throw new DuplicateMembershipException("Team name must be unique within the organization");
        }

        Team team = new Team();
        team.setOrganizationId(organizationId);
        team.setName(name);
        team.setDescription(description);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());

        Team saved = teamRepository.save(team);
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_CREATED", null, null, "{\"teamId\": \"" + saved.getId() + "\"}");
        return saved;
    }

    @Transactional(readOnly = true)
    public Team getTeam(UUID organizationId, UUID teamId, UUID currentUserId) {
        verifyActiveOrganizationMembership(currentUserId, organizationId);
        
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new TeamNotFoundException("Team not found"));

        if (!team.getOrganizationId().equals(organizationId)) {
            throw new OrganizationAccessDeniedException("Team does not belong to this organization");
        }
        return team;
    }

    @Transactional(readOnly = true)
    public List<Team> getTeams(UUID organizationId, UUID currentUserId) {
        verifyActiveOrganizationMembership(currentUserId, organizationId);
        return teamRepository.findByOrganizationId(organizationId);
    }

    @Transactional
    public Team updateTeam(UUID organizationId, UUID teamId, UUID currentUserId, String name, String description) {
        Team team = getTeam(organizationId, teamId, currentUserId); // Also verifies membership and organization isolation
        
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_UPDATE") &&
            !authorizationService.hasTeamPermission(currentUserId, organizationId, teamId, "TEAM_UPDATE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to update team");
        }

        if (!team.getName().equals(name)) {
            if (teamRepository.findByOrganizationIdAndName(organizationId, name).isPresent()) {
                throw new DuplicateMembershipException("Team name must be unique within the organization");
            }
            team.setName(name);
        }

        team.setDescription(description);
        team.setUpdatedAt(Instant.now());
        
        Team saved = teamRepository.save(team);
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_UPDATED", null, null, "{\"teamId\": \"" + saved.getId() + "\"}");
        return saved;
    }

    @Transactional
    public void deleteTeam(UUID organizationId, UUID teamId, UUID currentUserId) {
        Team team = getTeam(organizationId, teamId, currentUserId);
        
        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_DELETE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to delete team");
        }
        
        teamRepository.deleteById(team.getId());
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_DELETED", null, null, "{\"teamId\": \"" + teamId + "\"}");
    }

    @Transactional
    public TeamMembership addTeamMember(UUID organizationId, UUID teamId, UUID currentUserId, UUID targetUserId, UUID roleId) {
        getTeam(organizationId, teamId, currentUserId); // Verifies caller and team

        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_MEMBER_ADD") &&
            !authorizationService.hasTeamPermission(currentUserId, organizationId, teamId, "TEAM_MEMBER_ADD")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to add team member");
        }

        verifyActiveOrganizationMembership(targetUserId, organizationId); // Target must be in org

        if (teamMembershipRepository.findByTeamIdAndUserId(teamId, targetUserId).isPresent()) {
            throw new DuplicateMembershipException("User is already a member of this team");
        }

        Role targetRole = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (targetRole.getScope() != RoleScope.TEAM) {
            throw new InvalidRoleScopeException("Assigned role must be TEAM-scoped");
        }

        if (!targetRole.isSystemDefined() && !organizationId.equals(targetRole.getOrganizationId())) {
            throw new InvalidRoleScopeException("Assigned role does not belong to this organization");
        }

        TeamMembership membership = new TeamMembership();
        membership.setTeamId(teamId);
        membership.setUserId(targetUserId);
        membership.setRoleId(roleId);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setCreatedAt(Instant.now());
        membership.setUpdatedAt(Instant.now());

        TeamMembership saved = teamMembershipRepository.save(membership);
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_MEMBER_ADDED", null, null, "{\"teamId\": \"" + teamId + "\", \"targetUserId\": \"" + targetUserId + "\"}");
        return saved;
    }

    @Transactional
    public void removeTeamMember(UUID organizationId, UUID teamId, UUID currentUserId, UUID targetUserId) {
        getTeam(organizationId, teamId, currentUserId);

        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_MEMBER_REMOVE") &&
            !authorizationService.hasTeamPermission(currentUserId, organizationId, teamId, "TEAM_MEMBER_REMOVE")) {
            throw new OrganizationAccessDeniedException("Insufficient permissions to remove team member");
        }

        TeamMembership membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> new MembershipNotFoundException("Team membership not found"));
        
        teamMembershipRepository.deleteById(membership.getId());
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_MEMBER_REMOVED", null, null, "{\"teamId\": \"" + teamId + "\", \"targetUserId\": \"" + targetUserId + "\"}");
    }

    @Transactional
    public TeamMembership changeTeamMemberRole(UUID organizationId, UUID teamId, UUID currentUserId, UUID targetUserId, UUID newRoleId) {
        getTeam(organizationId, teamId, currentUserId);

        if (!authorizationService.hasOrganizationPermission(currentUserId, organizationId, "TEAM_MEMBER_ADD") &&
            !authorizationService.hasTeamPermission(currentUserId, organizationId, teamId, "TEAM_MEMBER_ADD")) {
            // Re-using TEAM_MEMBER_ADD/REMOVE or perhaps a specific ROLE_ASSIGN but restricted to team scope
            throw new OrganizationAccessDeniedException("Insufficient permissions to change team member role");
        }

        TeamMembership membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> new MembershipNotFoundException("Team membership not found"));

        Role targetRole = roleRepository.findById(newRoleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if (targetRole.getScope() != RoleScope.TEAM) {
            throw new InvalidRoleScopeException("Assigned role must be TEAM-scoped");
        }

        if (!targetRole.isSystemDefined() && !organizationId.equals(targetRole.getOrganizationId())) {
            throw new InvalidRoleScopeException("Assigned role does not belong to this organization");
        }

        membership.setRoleId(newRoleId);
        membership.setUpdatedAt(Instant.now());
        
        TeamMembership saved = teamMembershipRepository.save(membership);
        securityEventService.recordEvent(currentUserId, organizationId, "TEAM_MEMBER_ROLE_CHANGED", null, null, "{\"teamId\": \"" + teamId + "\", \"targetUserId\": \"" + targetUserId + "\"}");
        return saved;
    }
    
    @Transactional(readOnly = true)
    public List<TeamMembership> getTeamMembers(UUID organizationId, UUID teamId, UUID currentUserId) {
        getTeam(organizationId, teamId, currentUserId);
        return teamMembershipRepository.findByTeamId(teamId);
    }

    private OrganizationMembership verifyActiveOrganizationMembership(UUID userId, UUID organizationId) {
        OrganizationMembership membership = organizationMembershipRepository.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new OrganizationAccessDeniedException("User is not a member of this organization"));
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new OrganizationAccessDeniedException("User organization membership is not active");
        }
        return membership;
    }
}
