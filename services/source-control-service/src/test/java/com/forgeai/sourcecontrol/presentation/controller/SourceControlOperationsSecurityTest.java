package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.AuditService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.infrastructure.metrics.InstallationTelemetryReconciler;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.AuditEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceControlOperationsSecurityTest {

    @Mock
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private GitHubInstallationRepository installationRepository;

    private MeterRegistry meterRegistry;
    private SourceControlMetrics metrics;
    private InstallationTelemetryReconciler reconciler;
    private SourceControlOperationsController controller;

    private final UUID orgA = UUID.fromString("cc28c8a3-2224-4bad-9ab2-ea36ede52577");
    private final UUID orgB = UUID.fromString("e9cc470a-c6be-46d9-a6b4-48d5185c7fd9");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new SourceControlMetrics(meterRegistry);
        reconciler = new InstallationTelemetryReconciler(installationRepository, metrics);
        controller = new SourceControlOperationsController(outboxEventRepository, metrics, auditService);
    }

    @Test
    @DisplayName("Dead-letter list: missing internal attestation header returns HTTP 403 Forbidden")
    void getDeadLetterMissingAttestationReturns403() {
        ResponseEntity<List<OutboxEventEntity>> response = controller.getDeadLetterEvents(
                orgA.toString(), null, null, 0, 20);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("Dead-letter list: missing tenant context returns HTTP 403 Forbidden")
    void getDeadLetterMissingTenantReturns403() {
        ResponseEntity<List<OutboxEventEntity>> response = controller.getDeadLetterEvents(
                null, null, "attested", 0, 20);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("CASE E: Org A dead-letter list never returns NULL-owned events (strictly tenant filtered)")
    void caseE_getDeadLetterNeverReturnsNullOrCrossTenantEvents() {
        OutboxEventEntity eventA = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgA)
                .status("DEAD_LETTER")
                .deadLetterAt(Instant.now())
                .build();

        when(outboxEventRepository.findByOrganizationIdAndStatusOrderByDeadLetterAtDesc(
                eq(orgA), eq("DEAD_LETTER"), any(PageRequest.class)))
                .thenReturn(List.of(eventA));

        ResponseEntity<List<OutboxEventEntity>> response = controller.getDeadLetterEvents(
                orgA.toString(), null, "attested", 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getOrganizationId()).isEqualTo(orgA);

        // Verify Org B asking returns empty list (zero records for other tenant, never NULL events)
        when(outboxEventRepository.findByOrganizationIdAndStatusOrderByDeadLetterAtDesc(
                eq(orgB), eq("DEAD_LETTER"), any(PageRequest.class)))
                .thenReturn(List.of());

        ResponseEntity<List<OutboxEventEntity>> responseB = controller.getDeadLetterEvents(
                orgB.toString(), null, "attested", 0, 20);
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody()).isEmpty();
    }

    @Test
    @DisplayName("CASE A: DEAD_LETTER + organizationId == null + admin caller => replay denied")
    void caseA_nullOrganizationIdReplayDeniedEvenForAdmin() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity nullOrgEvent = OutboxEventEntity.builder()
                .id(eventId)
                .organizationId(null) // Unowned or legacy null event
                .status("DEAD_LETTER")
                .deadLetterAt(Instant.now())
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(nullOrgEvent));

        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, orgA.toString(), null, "admin-1", "ROLE_ADMIN", "outbox:replay", "attested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Forbidden: outbox event has no tenant ownership");
        verify(auditService).recordEvent(eq(orgA), eq("admin-1"), eq("OUTBOX_REPLAY_DENIED"), any(), eq(eventId.toString()), eq("REPLAY"), eq("DENIED"), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("CASE B: DEAD_LETTER + organizationId == Org A + Org B caller => 403 Forbidden")
    void caseB_crossTenantReplayBlockedWith403() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity eventA = OutboxEventEntity.builder()
                .id(eventId)
                .organizationId(orgA) // Belongs to Org A!
                .status("DEAD_LETTER")
                .deadLetterAt(Instant.now())
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(eventA));

        // Org B caller attempting replay on Org A event
        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, orgB.toString(), null, "operator-b", "ROLE_ADMIN", "outbox:replay", "attested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Forbidden: outbox event does not belong to your organization");
        verify(auditService).recordEvent(eq(orgB), eq("operator-b"), eq("OUTBOX_REPLAY_DENIED"), any(), eq(eventId.toString()), eq("REPLAY"), eq("DENIED"), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("CASE C: DEAD_LETTER + organizationId == Org A + Org A developer => 403 Forbidden")
    void caseC_developerReplayDenied403() {
        UUID eventId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, orgA.toString(), null, "dev-user", "DEVELOPER", "project:read", "attested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Forbidden: operational replay requires operator or organization admin privileges");
        verify(auditService).recordEvent(eq(orgA), eq("dev-user"), eq("OUTBOX_REPLAY_DENIED"), any(), eq(eventId.toString()), eq("REPLAY"), eq("DENIED"), any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("CASE D: DEAD_LETTER + organizationId == Org A + Org A admin/operator => success (200 OK)")
    void caseD_authorizedAdminReplaySucceeds() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity eventA = OutboxEventEntity.builder()
                .id(eventId)
                .organizationId(orgA)
                .status("DEAD_LETTER")
                .attemptCount(5)
                .deadLetterAt(Instant.now())
                .lastError("Timeout")
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(eventA));

        AuditEventEntity auditEvent = AuditEventEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(orgA)
                .action("REPLAY")
                .outcome("SUCCESS")
                .build();

        when(auditService.recordEvent(eq(orgA), eq("admin-user"), eq("OUTBOX_REPLAY_REQUESTED"), any(), eq(eventId.toString()), eq("REPLAY"), eq("SUCCESS"), any()))
                .thenReturn(auditEvent);

        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, orgA.toString(), null, "admin-user", "ROLE_ADMIN", "outbox:replay", "attested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "REPLAY_SCHEDULED");

        assertThat(eventA.getStatus()).isEqualTo("PENDING");
        assertThat(eventA.getAttemptCount()).isEqualTo(0);
        assertThat(eventA.getDeadLetterAt()).isNull();
        assertThat(eventA.getLastError()).isNull();

        verify(outboxEventRepository).save(eventA);
        verify(auditService).recordEvent(eq(orgA), eq("admin-user"), eq("OUTBOX_REPLAY_REQUESTED"), any(), eq(eventId.toString()), eq("REPLAY"), eq("SUCCESS"), any());
    }

    @Test
    @DisplayName("Outbox replay: missing internal attestation header returns HTTP 403 Forbidden")
    void replayMissingAttestationReturns403() {
        UUID eventId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, orgA.toString(), null, "admin-user", "ROLE_ADMIN", "outbox:replay", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Forbidden: untrusted internal context");
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("Outbox replay: missing tenant context returns HTTP 403 Forbidden")
    void replayMissingTenantReturns403() {
        UUID eventId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> response = controller.replayOutboxEvent(
                eventId, null, null, "user-1", "ROLE_ADMIN", "outbox:replay", "attested");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("Telemetry Reconciliation: Authoritative DB count == API count == Prometheus gauge")
    void telemetryReconciliationMatchesAuthoritativeState() {
        // Setup 2 active installations and 1 quarantined installation in DB
        GitHubInstallation inst1 = GitHubInstallation.builder()
                .organizationId(orgA)
                .installationId(101L)
                .status(InstallationStatus.ACTIVE)
                .build();

        GitHubInstallation inst2 = GitHubInstallation.builder()
                .organizationId(orgB)
                .installationId(102L)
                .status(InstallationStatus.ACTIVE)
                .build();

        GitHubInstallation quarantined = GitHubInstallation.builder()
                .organizationId(SourceControlMetrics.QUARANTINE_ORG_ID)
                .installationId(999L)
                .status(InstallationStatus.SUSPENDED)
                .build();

        when(installationRepository.findAll()).thenReturn(List.of(inst1, inst2, quarantined));

        // Reconcile
        reconciler.reconcile();

        // 1. Check Controller API response
        ResponseEntity<Map<String, Object>> statsResponse = controller.getGitHubStats();
        Map<String, Object> body = statsResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("activeInstallations")).isEqualTo(2);
        assertThat(body.get("quarantinedInstallations")).isEqualTo(1);
        assertThat(body.get("suspendedInstallations")).isEqualTo(0);

        // 2. Check Prometheus metrics gauge
        double prometheusActiveGauge = meterRegistry.get("github_installations_active").gauge().value();
        double prometheusQuarantinedGauge = meterRegistry.get("github_installations_quarantined").gauge().value();

        assertThat(prometheusActiveGauge).isEqualTo(2.0);
        assertThat(prometheusQuarantinedGauge).isEqualTo(1.0);

        // Prove: DB count (2) == API count (2) == Prometheus gauge (2.0)
        assertThat(body.get("activeInstallations")).isEqualTo((int) prometheusActiveGauge);
    }
}
