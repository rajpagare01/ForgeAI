package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.CreateInvitationRequest;
import com.forgeai.identity.api.dto.InvitationResponse;
import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.event.MemberRoleChangedEvent;
import com.forgeai.identity.domain.exception.OrganizationAccessDeniedException;
import com.forgeai.identity.domain.model.InvitationStatus;
import com.forgeai.identity.domain.model.MembershipStatus;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.InvitationJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.InvitationRepository;
import com.forgeai.identity.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationLifecycleTest {

    @Mock
    private InvitationRepository invitationRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationMembershipRepository membershipRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthorizationService authorizationService;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private OutboxService outboxService;

    private InvitationService invitationService;

    private UUID orgId;
    private UUID inviterId;
    private UUID inviteeId;
    private Role developerRole;
    private Organization organization;

    @BeforeEach
    void setUp() {
        invitationService = new InvitationService(
                invitationRepository,
                organizationRepository,
                membershipRepository,
                roleRepository,
                userRepository,
                authorizationService,
                securityEventService,
                outboxService
        );

        orgId = UUID.randomUUID();
        inviterId = UUID.randomUUID();
        inviteeId = UUID.randomUUID();

        organization = new Organization();
        organization.setId(orgId);
        organization.setName("ForgeAI Org");

        developerRole = new Role();
        developerRole.setId(UUID.randomUUID());
        developerRole.setName("DEVELOPER");
        developerRole.setScope(RoleScope.ORGANIZATION);
    }

    @Test
    @DisplayName("Create Invitation: Generates 7-day token hash and saves with PENDING status")
    void shouldCreateInvitationWithHashedToken() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(authorizationService.hasOrganizationPermission(inviterId, orgId, "MEMBER_INVITE")).thenReturn(true);
        when(roleRepository.findById(developerRole.getId())).thenReturn(Optional.of(developerRole));
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(invitationRepository.save(any(InvitationJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateInvitationRequest request = new CreateInvitationRequest("newdev@example.com", developerRole.getId());
        InvitationResponse response = invitationService.createInvitation(orgId, inviterId, request);

        assertThat(response).isNotNull();
        assertThat(response.email()).isEqualTo("newdev@example.com");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.token()).hasSize(64); // Raw token returned for dispatch
        assertThat(response.expiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));

        ArgumentCaptor<InvitationJpaEntity> entityCaptor = ArgumentCaptor.forClass(InvitationJpaEntity.class);
        verify(invitationRepository).save(entityCaptor.capture());
        InvitationJpaEntity saved = entityCaptor.getValue();
        // Database stores SHA-256 hash (64 hex chars)
        assertThat(saved.getToken()).hasSize(64);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Create Invitation: Rejected if caller lacks MEMBER_INVITE permission")
    void shouldRejectWhenCallerLacksPermission() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(authorizationService.hasOrganizationPermission(inviterId, orgId, "MEMBER_INVITE")).thenReturn(false);

        CreateInvitationRequest request = new CreateInvitationRequest("newdev@example.com", developerRole.getId());
        assertThrows(OrganizationAccessDeniedException.class, () ->
                invitationService.createInvitation(orgId, inviterId, request)
        );
    }

    @Test
    @DisplayName("Accept Invitation: Atomically creates organization_membership, marks ACCEPTED, and emits outbox event")
    void shouldAcceptInvitationAndEmitOutboxEvent() {
        String rawToken = "invitationRawToken12345678901234567890123456789012345678901234567890";
        String tokenHash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            tokenHash = sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        InvitationJpaEntity invitation = new InvitationJpaEntity();
        invitation.setId(UUID.randomUUID());
        invitation.setOrganizationId(orgId);
        invitation.setEmail("invitee@example.com");
        invitation.setRoleId(developerRole.getId());
        invitation.setInvitedBy(inviterId);
        invitation.setToken(tokenHash);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        User inviteeUser = new User();
        inviteeUser.setId(inviteeId);
        inviteeUser.setEmail(new Email("invitee@example.com"));
        inviteeUser.setUsername(new Username("invitee"));

        when(invitationRepository.findByToken(tokenHash)).thenReturn(Optional.of(invitation));
        when(userRepository.findById(inviteeId)).thenReturn(Optional.of(inviteeUser));
        when(roleRepository.findById(developerRole.getId())).thenReturn(Optional.of(developerRole));
        when(membershipRepository.findByUserIdAndOrganizationId(inviteeId, orgId)).thenReturn(Optional.empty());
        when(membershipRepository.save(any(OrganizationMembership.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invitationRepository.save(any(InvitationJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrganizationMembership membership = invitationService.acceptInvitation(rawToken, inviteeId);

        assertThat(membership.getUserId()).isEqualTo(inviteeId);
        assertThat(membership.getOrganizationId()).isEqualTo(orgId);
        assertThat(membership.getRoleId()).isEqualTo(developerRole.getId());
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);

        assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");
        verify(invitationRepository).save(invitation);

        // Verify MemberRoleChangedEvent published to outbox
        verify(outboxService).publish(any(MemberRoleChangedEvent.class), isNull());
    }

    @Test
    @DisplayName("Cancel Invitation: Updates status to CANCELLED")
    void shouldCancelInvitation() {
        UUID invitationId = UUID.randomUUID();
        InvitationJpaEntity invitation = new InvitationJpaEntity();
        invitation.setId(invitationId);
        invitation.setOrganizationId(orgId);
        invitation.setStatus("PENDING");

        when(authorizationService.hasOrganizationPermission(inviterId, orgId, "MEMBER_INVITE")).thenReturn(true);
        when(invitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));

        invitationService.cancelInvitation(orgId, invitationId, inviterId);

        assertThat(invitation.getStatus()).isEqualTo("CANCELLED");
        verify(invitationRepository).save(invitation);
    }
}
