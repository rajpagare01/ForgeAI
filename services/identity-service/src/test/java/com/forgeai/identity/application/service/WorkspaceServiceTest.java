package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.application.port.out.WorkspaceRepository;
import com.forgeai.identity.domain.exception.AccessDeniedException;
import com.forgeai.identity.domain.exception.ResourceNotFoundException;
import com.forgeai.identity.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    private WorkspaceService workspaceService;

    private UUID userId;
    private UUID otherUserId;
    private UUID orgId;
    private Workspace personalWorkspace;
    private Workspace orgWorkspace;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                workspaceRepository,
                organizationMembershipRepository,
                organizationRepository,
                userRepository
        );

        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        personalWorkspace = Workspace.builder()
                .id(UUID.randomUUID())
                .type(WorkspaceType.PERSONAL)
                .ownerUserId(userId)
                .organizationId(null)
                .name("testuser's Workspace")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        orgWorkspace = Workspace.builder()
                .id(orgId)
                .type(WorkspaceType.ORGANIZATION)
                .ownerUserId(userId)
                .organizationId(orgId)
                .name("Acme Corp Workspace")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("UT-WS-01: Personal workspace is created for user if none exists")
    void testGetOrCreatePersonalWorkspace_CreatesNew() {
        when(workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId))
                .thenReturn(Optional.empty());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace ws = invocation.getArgument(0);
            ws.setId(UUID.randomUUID());
            return ws;
        });

        Workspace created = workspaceService.getOrCreatePersonalWorkspace(userId, "testuser");

        assertNotNull(created);
        assertEquals(WorkspaceType.PERSONAL, created.getType());
        assertEquals(userId, created.getOwnerUserId());
        assertNull(created.getOrganizationId());
        assertEquals("testuser's Workspace", created.getName());
        verify(workspaceRepository).save(any(Workspace.class));
    }

    @Test
    @DisplayName("UT-WS-02: Idempotent personal workspace fetch returns existing workspace")
    void testGetOrCreatePersonalWorkspace_ReturnsExisting() {
        when(workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId))
                .thenReturn(Optional.of(personalWorkspace));

        Workspace result = workspaceService.getOrCreatePersonalWorkspace(userId, "testuser");

        assertNotNull(result);
        assertEquals(personalWorkspace.getId(), result.getId());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-WS-03: Concurrent race on personal workspace handles DataIntegrityViolation gracefully")
    void testGetOrCreatePersonalWorkspace_ConcurrentRaceHandling() {
        when(workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(personalWorkspace));

        when(workspaceRepository.save(any(Workspace.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        Workspace result = workspaceService.getOrCreatePersonalWorkspace(userId, "testuser");

        assertNotNull(result);
        assertEquals(personalWorkspace.getId(), result.getId());
    }

    @Test
    @DisplayName("UT-WS-04: Owner can read their personal workspace")
    void testGetWorkspaceById_OwnerCanReadPersonalWorkspace() {
        when(workspaceRepository.findById(personalWorkspace.getId()))
                .thenReturn(Optional.of(personalWorkspace));

        Workspace result = workspaceService.getWorkspaceById(personalWorkspace.getId(), userId);

        assertNotNull(result);
        assertEquals(personalWorkspace.getId(), result.getId());
    }

    @Test
    @DisplayName("UT-WS-05: Owner can update their personal workspace name")
    void testUpdateWorkspace_OwnerCanUpdate() {
        when(workspaceRepository.findById(personalWorkspace.getId()))
                .thenReturn(Optional.of(personalWorkspace));
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Workspace updated = workspaceService.updateWorkspace(personalWorkspace.getId(), userId, "My New Studio");

        assertNotNull(updated);
        assertEquals("My New Studio", updated.getName());
        verify(workspaceRepository).save(personalWorkspace);
    }

    @Test
    @DisplayName("UT-WS-06: Non-owner cannot read personal workspace (throws AccessDeniedException)")
    void testGetWorkspaceById_NonOwnerDeniedForPersonalWorkspace() {
        when(workspaceRepository.findById(personalWorkspace.getId()))
                .thenReturn(Optional.of(personalWorkspace));

        assertThrows(AccessDeniedException.class, () ->
                workspaceService.getWorkspaceById(personalWorkspace.getId(), otherUserId)
        );
    }

    @Test
    @DisplayName("UT-WS-07: Non-owner cannot update personal workspace (throws AccessDeniedException)")
    void testUpdateWorkspace_NonOwnerDenied() {
        when(workspaceRepository.findById(personalWorkspace.getId()))
                .thenReturn(Optional.of(personalWorkspace));

        assertThrows(AccessDeniedException.class, () ->
                workspaceService.updateWorkspace(personalWorkspace.getId(), otherUserId, "Hacked Workspace")
        );
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Organization workspace: active member has access")
    void testGetWorkspaceById_OrgMemberAllowed() {
        when(workspaceRepository.findById(orgWorkspace.getId()))
                .thenReturn(Optional.of(orgWorkspace));

        OrganizationMembership membership = new OrganizationMembership();
        membership.setOrganizationId(orgId);
        membership.setUserId(userId);
        membership.setStatus(MembershipStatus.ACTIVE);

        when(organizationMembershipRepository.findByUserIdAndOrganizationId(userId, orgId))
                .thenReturn(Optional.of(membership));

        Workspace result = workspaceService.getWorkspaceById(orgWorkspace.getId(), userId);
        assertNotNull(result);
        assertEquals(orgWorkspace.getId(), result.getId());
    }

    @Test
    @DisplayName("Organization workspace: non-member is denied (throws AccessDeniedException)")
    void testGetWorkspaceById_NonOrgMemberDenied() {
        when(workspaceRepository.findById(orgWorkspace.getId()))
                .thenReturn(Optional.of(orgWorkspace));

        when(organizationMembershipRepository.findByUserIdAndOrganizationId(otherUserId, orgId))
                .thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () ->
                workspaceService.getWorkspaceById(orgWorkspace.getId(), otherUserId)
        );
    }

    @Test
    @DisplayName("User workspaces lists personal workspace and active organization workspaces")
    void testGetUserWorkspaces_ReturnsCombinedWorkspaces() {
        when(workspaceRepository.findByTypeAndOwnerUserId(WorkspaceType.PERSONAL, userId))
                .thenReturn(Optional.of(personalWorkspace));

        OrganizationMembership membership = new OrganizationMembership();
        membership.setOrganizationId(orgId);
        membership.setUserId(userId);
        membership.setStatus(MembershipStatus.ACTIVE);

        when(organizationMembershipRepository.findByUserId(userId))
                .thenReturn(List.of(membership));
        when(workspaceRepository.findByTypeAndOrganizationId(WorkspaceType.ORGANIZATION, orgId))
                .thenReturn(Optional.of(orgWorkspace));

        List<Workspace> workspaces = workspaceService.getUserWorkspaces(userId);

        assertEquals(2, workspaces.size());
        assertEquals(WorkspaceType.PERSONAL, workspaces.get(0).getType());
        assertEquals(WorkspaceType.ORGANIZATION, workspaces.get(1).getType());
    }
}
