package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.*;
import com.forgeai.identity.application.service.TeamService;
import com.forgeai.identity.domain.model.Team;
import com.forgeai.identity.domain.model.TeamMembership;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping
    public ResponseEntity<Team> createTeam(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateTeamRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Team team = teamService.createTeam(organizationId, userId, request.getName(), request.getDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(team);
    }

    @GetMapping
    public ResponseEntity<List<Team>> getTeams(
            @PathVariable UUID organizationId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(teamService.getTeams(organizationId, userId));
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<Team> getTeam(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(teamService.getTeam(organizationId, teamId, userId));
    }

    @PatchMapping("/{teamId}")
    public ResponseEntity<Team> updateTeam(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            @RequestBody UpdateTeamRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Team team = teamService.updateTeam(organizationId, teamId, userId, request.getName(), request.getDescription());
        return ResponseEntity.ok(team);
    }

    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> deleteTeam(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        teamService.deleteTeam(organizationId, teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/members")
    public ResponseEntity<List<TeamMembership>> getTeamMembers(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(teamService.getTeamMembers(organizationId, teamId, userId));
    }

    @PostMapping("/{teamId}/members")
    public ResponseEntity<TeamMembership> addTeamMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            @RequestBody AddTeamMemberRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        TeamMembership membership = teamService.addTeamMember(organizationId, teamId, userId, request.getUserId(), request.getRoleId());
        return ResponseEntity.status(HttpStatus.CREATED).body(membership);
    }

    @DeleteMapping("/{teamId}/members/{memberId}")
    public ResponseEntity<Void> removeTeamMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            @PathVariable UUID memberId,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        teamService.removeTeamMember(organizationId, teamId, userId, memberId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{teamId}/members/{memberId}/role")
    public ResponseEntity<TeamMembership> changeTeamMemberRole(
            @PathVariable UUID organizationId,
            @PathVariable UUID teamId,
            @PathVariable UUID memberId,
            @RequestBody ChangeTeamMemberRoleRequest request,
            Authentication authentication) {
        UUID userId = extractUserId(authentication);
        TeamMembership membership = teamService.changeTeamMemberRole(organizationId, teamId, userId, memberId, request.getRoleId());
        return ResponseEntity.ok(membership);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID)) {
            throw new com.forgeai.identity.domain.exception.InvalidCredentialsException("Authentication required");
        }
        return (UUID) authentication.getPrincipal();
    }
}
