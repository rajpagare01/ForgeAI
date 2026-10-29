package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.application.port.out.WorkspaceRepository;
import com.forgeai.identity.domain.exception.AccessDeniedException;
import com.forgeai.identity.domain.exception.ResourceNotFoundException;
import com.forgeai.identity.domain.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository
    ) {
        this.workspaceRepository = workspaceRepository;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Gets or idempotently creates a PERSONAL workspace for the given user.
     * Guarantees at most ONE personal workspace per user via DB unique index.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Workspace getOrCreatePersonalWorkspace(UUID userId, String username) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to get or create a personal workspace");
        }

        Optional<Workspace> existing = workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String displayName = "Personal Workspace";
        if (username != null && !username.isBlank()) {
            displayName = username + "'s Workspace";
        } else {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isPresent() && userOpt.get().getUsername() != null) {
                displayName = userOpt.get().getUsername() + "'s Workspace";
            }
        }

        Instant now = Instant.now();
        Workspace newWorkspace = Workspace.builder()
                .type(WorkspaceType.PERSONAL)
                .ownerUserId(userId)
                .organizationId(null)
                .name(displayName)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            Workspace saved = workspaceRepository.save(newWorkspace);
            log.info("Created PERSONAL workspace {} for user {}", saved.getId(), userId);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent insertion detected for personal workspace of user {}; retrieving existing", userId);
            return workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Gets or idempotently creates an ORGANIZATION workspace for the given organization.
     */
    @Transactional
    public Workspace getOrCreateOrganizationWorkspace(UUID organizationId, UUID ownerUserId, String orgName) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID is required to get or create an organization workspace");
        }

        Optional<Workspace> existing = workspaceRepository.findByTypeAndOrganizationId(WorkspaceType.ORGANIZATION, organizationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        String displayName = orgName;
        if (displayName == null || displayName.isBlank()) {
            Optional<Organization> orgOpt = organizationRepository.findById(organizationId);
            displayName = orgOpt.map(Organization::getName).orElse("Organization Workspace");
        }

        UUID resolvedOwner = ownerUserId;
        if (resolvedOwner == null) {
            resolvedOwner = organizationMembershipRepository.findByOrganizationId(organizationId).stream()
                    .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                    .map(OrganizationMembership::getUserId)
                    .findFirst()
                    .orElse(UUID.fromString("00000000-0000-0000-0000-000000000000"));
        }

        Instant now = Instant.now();
        Workspace orgWorkspace = Workspace.builder()
                .id(organizationId)
                .type(WorkspaceType.ORGANIZATION)
                .ownerUserId(resolvedOwner)
                .organizationId(organizationId)
                .name(displayName)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            Workspace saved = workspaceRepository.save(orgWorkspace);
            log.info("Created ORGANIZATION workspace {} for organization {}", saved.getId(), organizationId);
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent insertion detected for organization workspace of org {}; retrieving existing", organizationId);
            return workspaceRepository.findByTypeAndOrganizationId(WorkspaceType.ORGANIZATION, organizationId)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Returns all workspaces accessible by the authenticated user:
     * - The user's PERSONAL workspace (auto-provisioned if missing).
     * - ORGANIZATION workspaces for all organizations where user is an ACTIVE member.
     */
    @Transactional
    public List<Workspace> getUserWorkspaces(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to fetch user workspaces");
        }

        List<Workspace> result = new ArrayList<>();

        // 1. Personal Workspace
        Workspace personal = getOrCreatePersonalWorkspace(userId, null);
        result.add(personal);

        // 2. Organization Workspaces
        List<OrganizationMembership> memberships = organizationMembershipRepository.findByUserId(userId).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .collect(Collectors.toList());

        for (OrganizationMembership membership : memberships) {
            Workspace orgWorkspace = getOrCreateOrganizationWorkspace(
                    membership.getOrganizationId(),
                    membership.getUserId(),
                    null
            );
            result.add(orgWorkspace);
        }

        return result;
    }

    /**
     * Retrieves workspace by ID and verifies caller authorization:
     * - PERSONAL: caller must be owner_user_id.
     * - ORGANIZATION: caller must be an active member of organization_id.
     */
    @Transactional(readOnly = true)
    public Workspace getWorkspaceById(UUID workspaceId, UUID userId) {
        if (workspaceId == null || userId == null) {
            throw new IllegalArgumentException("Workspace ID and User ID are required");
        }

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found: " + workspaceId));

        if (workspace.getType() == WorkspaceType.PERSONAL) {
            if (!userId.equals(workspace.getOwnerUserId())) {
                log.warn("Access denied: User {} attempted to access personal workspace {} owned by {}",
                        userId, workspaceId, workspace.getOwnerUserId());
                throw new AccessDeniedException("Access denied: You do not own this personal workspace");
            }
        } else if (workspace.getType() == WorkspaceType.ORGANIZATION) {
            Optional<OrganizationMembership> membership = organizationMembershipRepository
                    .findByUserIdAndOrganizationId(userId, workspace.getOrganizationId());
            if (membership.isEmpty() || membership.get().getStatus() != MembershipStatus.ACTIVE) {
                log.warn("Access denied: User {} is not an active member of organization {}",
                        userId, workspace.getOrganizationId());
                throw new AccessDeniedException("Access denied: You are not a member of this organization");
            }
        }

        return workspace;
    }

    /**
     * Updates a workspace name (only owner can rename).
     */
    @Transactional
    public Workspace updateWorkspace(UUID workspaceId, UUID userId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Workspace name cannot be blank");
        }

        Workspace workspace = getWorkspaceById(workspaceId, userId);
        if (workspace.getType() == WorkspaceType.PERSONAL) {
            if (!userId.equals(workspace.getOwnerUserId())) {
                throw new AccessDeniedException("Only the owner can modify a personal workspace");
            }
        }

        workspace.setName(newName.trim());
        workspace.setUpdatedAt(Instant.now());
        return workspaceRepository.save(workspace);
    }
}
