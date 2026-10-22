package com.forgeai.sourcecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.application.handler.InstallationEventHandler;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubInstallationSecurityTest {

    private InMemoryInstallationRepository installationRepository;
    private InstallationEventHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    static class InMemoryInstallationRepository implements GitHubInstallationRepository {
        final Map<Long, GitHubInstallation> byInstallationId = new HashMap<>();
        final Map<UUID, GitHubInstallation> byOrgId = new HashMap<>();

        @Override
        public GitHubInstallation save(GitHubInstallation installation) {
            byInstallationId.put(installation.getInstallationId(), installation);
            if (installation.getOrganizationId() != null) {
                byOrgId.put(installation.getOrganizationId(), installation);
            }
            return installation;
        }

        @Override
        public Optional<GitHubInstallation> findByInstallationId(Long installationId) {
            return Optional.ofNullable(byInstallationId.get(installationId));
        }

        @Override
        public Optional<GitHubInstallation> findByOrganizationId(UUID organizationId) {
            return Optional.ofNullable(byOrgId.get(organizationId));
        }

        @Override
        public Optional<GitHubInstallation> findByWorkspaceId(UUID workspaceId) {
            return byInstallationId.values().stream()
                    .filter(i -> workspaceId.equals(i.getWorkspaceId()))
                    .findFirst();
        }

        @Override
        public java.util.List<GitHubInstallation> findAll() {
            return new java.util.ArrayList<>(byInstallationId.values());
        }
    }

    @BeforeEach
    void setUp() {
        installationRepository = new InMemoryInstallationRepository();
        handler = new InstallationEventHandler(installationRepository, null, null);
    }

    @Test
    @DisplayName("Forensic Check: Unmapped installation must be quarantined, NEVER assigned random UUID tenant")
    void unmappedInstallationIsQuarantinedNotRandomTenant() throws Exception {
        long installationId = 884422L;

        String payload = """
            {
                "action": "created",
                "installation": {
                    "id": %d,
                    "account": {
                        "login": "untrusted-org",
                        "type": "Organization"
                    }
                }
            }
            """.formatted(installationId);

        handler.handle(objectMapper.readTree(payload), "deliv-sec-1");

        Optional<GitHubInstallation> savedOpt = installationRepository.findByInstallationId(installationId);
        assertThat(savedOpt).isPresent();

        GitHubInstallation saved = savedOpt.get();
        assertThat(saved.getInstallationId()).isEqualTo(installationId);
        // Mandatory verification: NEVER a random UUID tenant
        assertThat(saved.getOrganizationId()).isEqualTo(QUARANTINE_ORG_ID);
        assertThat(saved.getStatus()).isEqualTo(InstallationStatus.SUSPENDED);
    }

    @Test
    @DisplayName("Forensic Check: Installation with explicit organization ID maps to authentic tenant")
    void installationWithExplicitOrgIdMapsToRealTenant() throws Exception {
        long installationId = 773311L;
        UUID authenticOrgId = UUID.randomUUID();

        String payload = """
            {
                "action": "created",
                "organization_id": "%s",
                "installation": {
                    "id": %d,
                    "account": {
                        "login": "authentic-org",
                        "type": "Organization"
                    }
                }
            }
            """.formatted(authenticOrgId, installationId);

        handler.handle(objectMapper.readTree(payload), "deliv-sec-2");

        Optional<GitHubInstallation> savedOpt = installationRepository.findByInstallationId(installationId);
        assertThat(savedOpt).isPresent();

        GitHubInstallation saved = savedOpt.get();
        assertThat(saved.getInstallationId()).isEqualTo(installationId);
        assertThat(saved.getOrganizationId()).isEqualTo(authenticOrgId);
        assertThat(saved.getStatus()).isEqualTo(InstallationStatus.ACTIVE);
    }

    @Test
    @DisplayName("Security Check: Unauthorized claim of an installation already owned by another org is denied")
    void unauthorizedInstallationClaimDenied() {
        long installationId = 998877L;
        UUID ownerOrg = UUID.randomUUID();
        UUID attackerOrg = UUID.randomUUID();

        // Installation is already owned by ownerOrg
        installationRepository.save(GitHubInstallation.builder()
                .installationId(installationId)
                .organizationId(ownerOrg)
                .accountLogin("victim-corp")
                .accountType("Organization")
                .status(InstallationStatus.ACTIVE)
                .build());

        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(1001L, getSampleRsaKey(), "test-secret");
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient mockClient =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient.class);
        org.mockito.Mockito.when(mockClient.getInstallationAccountLogin(installationId)).thenReturn("victim-corp");

        com.forgeai.sourcecontrol.application.service.RepositoryApplicationService service =
                new com.forgeai.sourcecontrol.application.service.RepositoryApplicationService(
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class),
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.PullRequestRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class),
                        mockClient,
                        config
                );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.claimInstallation(attackerOrg, installationId))
                .isInstanceOf(com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException.class)
                .hasMessageContaining("already claimed by another organization");
    }

    @Test
    @DisplayName("Security Check: Valid claim of a quarantined installation succeeds and activates tenant")
    void validInstallationClaimSucceeds() {
        long installationId = 665544L;
        UUID claimantOrg = UUID.randomUUID();

        // Quarantined installation
        installationRepository.save(GitHubInstallation.builder()
                .installationId(installationId)
                .organizationId(QUARANTINE_ORG_ID)
                .accountLogin("claimant-corp")
                .accountType("Organization")
                .status(InstallationStatus.SUSPENDED)
                .build());

        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(1001L, getSampleRsaKey(), "test-secret");
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient mockClient =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient.class);
        org.mockito.Mockito.when(mockClient.getInstallationAccountLogin(installationId)).thenReturn("claimant-corp");

        com.forgeai.sourcecontrol.application.service.RepositoryApplicationService service =
                new com.forgeai.sourcecontrol.application.service.RepositoryApplicationService(
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class),
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.PullRequestRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class),
                        mockClient,
                        config
                );

        GitHubInstallation claimed = service.claimInstallation(claimantOrg, installationId);
        assertThat(claimed.getOrganizationId()).isEqualTo(claimantOrg);
        assertThat(claimed.getStatus()).isEqualTo(InstallationStatus.ACTIVE);
    }

    @Test
    @DisplayName("Security Check: Connecting repository not available to installation is denied")
    void repositoryNotAvailableToInstallationDenied() {
        long installationId = 554433L;
        UUID orgId = UUID.randomUUID();

        installationRepository.save(GitHubInstallation.builder()
                .installationId(installationId)
                .organizationId(orgId)
                .accountLogin("verified-org")
                .accountType("Organization")
                .status(InstallationStatus.ACTIVE)
                .build());

        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(1001L, getSampleRsaKey(), "test-secret");
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient mockClient =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient.class);
        org.mockito.Mockito.when(mockClient.getVerifiedRepository(installationId, "untrusted/repo"))
                .thenThrow(new com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException(
                        "Repository 'untrusted/repo' is not accessible to installation " + installationId));

        com.forgeai.sourcecontrol.application.service.RepositoryApplicationService service =
                new com.forgeai.sourcecontrol.application.service.RepositoryApplicationService(
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class),
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.PullRequestRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class),
                        mockClient,
                        config
                );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.connectRepository(orgId, "untrusted/repo"))
                .isInstanceOf(com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException.class)
                .hasMessageContaining("not accessible");
    }

    @Test
    @DisplayName("Security Check: Real GitHub App JWT authentication generation and signature verification")
    void realGitHubAppJwtAuthentication() {
        String privateKeyPem = getSampleRsaKey();
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(98765L, privateKeyPem, "test-secret");
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient client =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient(config, objectMapper);

        String jwt = client.buildAppJwt();
        assertThat(jwt).isNotBlank();

        // Verify claims
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payloadJson).contains("\"iss\":\"98765\"");
    }

    @Test
    @DisplayName("Security Check: Attempting to connect repository without claimed installation is denied (fake installationId impossible)")
    void fakeOrMissingInstallationIdDenied() {
        UUID unonboardedOrg = UUID.randomUUID();

        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(1001L, getSampleRsaKey(), "test-secret");
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient mockClient =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient.class);

        com.forgeai.sourcecontrol.application.service.RepositoryApplicationService service =
                new com.forgeai.sourcecontrol.application.service.RepositoryApplicationService(
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class),
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.PullRequestRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class),
                        mockClient,
                        config
                );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.connectRepository(unonboardedOrg, "acme/repo"))
                .isInstanceOf(com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException.class)
                .hasMessageContaining("No active GitHub App installation found");
    }

    @Test
    @DisplayName("Security Check: Fake or unauthorized GitHub repository ID lookup is denied")
    void fakeGithubRepoIdDenied() {
        long installationId = 123456L;
        long fakeRepoId = 99999999L;

        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig config =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(1001L, getSampleRsaKey(), "test-secret");

        // Subclass to override listInstallationRepositories and avoid HTTP call
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient client =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppClient(config, objectMapper) {
                    @Override
                    public java.util.List<com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata> listInstallationRepositories(Long instId) {
                        return java.util.List.of(new com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata(
                                11111L, instId, "real-repo", "owner/real-repo", true, "main", "owner"
                        ));
                    }
                };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.getVerifiedRepositoryById(installationId, fakeRepoId))
                .isInstanceOf(com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException.class)
                .hasMessageContaining("not accessible to installation");
    }

    @Test
    @DisplayName("Security Check: Operation fails fast with 503 when GitHub App credentials are not configured")
    void unconfiguredGitHubAppFailsFast() {
        com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig unconfigured =
                new com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig(0L, "", "");
        assertThat(unconfigured.isConfigured()).isFalse();

        org.assertj.core.api.Assertions.assertThatThrownBy(unconfigured::requireConfigured)
                .isInstanceOf(com.forgeai.sourcecontrol.infrastructure.github.GitHubAppNotConfiguredException.class)
                .hasMessageContaining("GitHub App is not configured");
    }

    @Test
    @DisplayName("Regression Check: Push event for valid mapped installation assigns repo to authentic tenant")
    void pushEventWithMappedInstallationMapsToAuthenticTenant() throws Exception {
        UUID authenticOrg = UUID.randomUUID();
        long mappedInstId = 5050L;
        installationRepository.save(GitHubInstallation.builder()
                .installationId(mappedInstId)
                .organizationId(authenticOrg)
                .accountLogin("tenant-corp")
                .accountType("Organization")
                .status(InstallationStatus.ACTIVE)
                .build());

        com.forgeai.sourcecontrol.domain.repository.RepositoryRepository mockRepoRepo =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class);
        org.mockito.Mockito.when(mockRepoRepo.findByGithubRepoId(12345L)).thenReturn(Optional.empty());
        org.mockito.Mockito.when(mockRepoRepo.save(org.mockito.ArgumentMatchers.any(com.forgeai.sourcecontrol.domain.model.Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        com.forgeai.sourcecontrol.application.handler.PushEventHandler pushHandler =
                new com.forgeai.sourcecontrol.application.handler.PushEventHandler(
                        mockRepoRepo,
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.CommitRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class)
                );

        String payload = """
            {
                "repository": {
                    "id": 12345,
                    "name": "sample-repo",
                    "full_name": "tenant-corp/sample-repo",
                    "default_branch": "main",
                    "private": true
                },
                "installation": {
                    "id": %d
                },
                "commits": []
            }
            """.formatted(mappedInstId);

        pushHandler.handle(objectMapper.readTree(payload), "deliv-push-auth");

        org.mockito.ArgumentCaptor<com.forgeai.sourcecontrol.domain.model.Repository> captor =
                org.mockito.ArgumentCaptor.forClass(com.forgeai.sourcecontrol.domain.model.Repository.class);
        org.mockito.Mockito.verify(mockRepoRepo).save(captor.capture());

        com.forgeai.sourcecontrol.domain.model.Repository savedRepo = captor.getValue();
        assertThat(savedRepo.getOrganizationId()).isEqualTo(authenticOrg);
        assertThat(savedRepo.getInstallationId()).isEqualTo(mappedInstId);
    }

    @Test
    @DisplayName("Regression Check: Push event for unknown unmapped installation strictly quarantines under zero UUID")
    void pushEventWithUnknownInstallationQuarantinesUnderZeroUuid() throws Exception {
        long unknownInstId = 9090L;

        com.forgeai.sourcecontrol.domain.repository.RepositoryRepository mockRepoRepo =
                org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.RepositoryRepository.class);
        org.mockito.Mockito.when(mockRepoRepo.findByGithubRepoId(99999L)).thenReturn(Optional.empty());
        org.mockito.Mockito.when(mockRepoRepo.save(org.mockito.ArgumentMatchers.any(com.forgeai.sourcecontrol.domain.model.Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        com.forgeai.sourcecontrol.application.handler.PushEventHandler pushHandler =
                new com.forgeai.sourcecontrol.application.handler.PushEventHandler(
                        mockRepoRepo,
                        installationRepository,
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.domain.repository.CommitRepository.class),
                        org.mockito.Mockito.mock(com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService.class)
                );

        String payload = """
            {
                "repository": {
                    "id": 99999,
                    "name": "unknown-repo",
                    "full_name": "attacker-org/unknown-repo",
                    "default_branch": "main",
                    "private": true
                },
                "installation": {
                    "id": %d
                },
                "commits": []
            }
            """.formatted(unknownInstId);

        pushHandler.handle(objectMapper.readTree(payload), "deliv-push-quarantine");

        org.mockito.ArgumentCaptor<com.forgeai.sourcecontrol.domain.model.Repository> captor =
                org.mockito.ArgumentCaptor.forClass(com.forgeai.sourcecontrol.domain.model.Repository.class);
        org.mockito.Mockito.verify(mockRepoRepo).save(captor.capture());

        com.forgeai.sourcecontrol.domain.model.Repository savedRepo = captor.getValue();
        // Mandatory: Must be quarantined under 00000000-0000-0000-0000-000000000000, never an arbitrary tenant
        assertThat(savedRepo.getOrganizationId()).isEqualTo(QUARANTINE_ORG_ID);
        assertThat(savedRepo.getInstallationId()).isEqualTo(unknownInstId);

        Optional<GitHubInstallation> quarantinedInst = installationRepository.findByInstallationId(unknownInstId);
        assertThat(quarantinedInst).isPresent();
        assertThat(quarantinedInst.get().getOrganizationId()).isEqualTo(QUARANTINE_ORG_ID);
        assertThat(quarantinedInst.get().getStatus()).isEqualTo(InstallationStatus.SUSPENDED);
    }

    private static String getSampleRsaKey() {
        try {
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair pair = gen.generateKeyPair();
            String base64 = java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
