package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.domain.model.PullRequestStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PullRequestChangesEndpointIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    private UUID orgId;
    private Repository repo;
    private PullRequest pr;

    @BeforeEach
    void setup() {
        orgId = UUID.randomUUID();
        long randomGithubRepoId = (long) (Math.random() * 1_000_000_000L) + 100_000L;
        repo = Repository.builder()
                .organizationId(orgId)
                .githubRepoId(randomGithubRepoId)
                .name("test-repo-" + randomGithubRepoId)
                .fullName("forgeai/test-repo")
                .defaultBranch("main")
                .isPrivate(true)
                .installationId(12345L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repo = repositoryRepository.save(repo);

        pr = PullRequest.builder()
                .organizationId(orgId)
                .repositoryId(repo.getId())
                .githubPrId(777111L)
                .number(15)
                .title("Add Payment Gateway")
                .body("Resolves TASK-101")
                .sourceBranch("feat/payment")
                .targetBranch("main")
                .status(PullRequestStatus.OPEN)
                .authorGithubId("dev1")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        pr = pullRequestRepository.save(pr);
    }

    @Test
    @DisplayName("GET /api/v1/pull-requests/{number} returns persisted PR for authorized organization")
    void getPullRequestReturnsPersistedPR() throws Exception {
        mockMvc.perform(get("/api/v1/pull-requests/15")
                        .param("repositoryId", repo.getId().toString())
                        .header("X-Org-Id", orgId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(15))
                .andExpect(jsonPath("$.title").value("Add Payment Gateway"))
                .andExpect(jsonPath("$.sourceBranch").value("feat/payment"));
    }

    @Test
    @DisplayName("GET /api/v1/pull-requests/{number}/changes returns change set (falling back to persisted if GitHub App unconfigured in test)")
    void getPullRequestChangesReturnsChangeSet() throws Exception {
        mockMvc.perform(get("/api/v1/pull-requests/15/changes")
                        .param("repositoryId", repo.getId().toString())
                        .header("X-Org-Id", orgId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(15))
                .andExpect(jsonPath("$.title").value("Add Payment Gateway"))
                .andExpect(jsonPath("$.repositoryFullName").value("forgeai/test-repo"));
    }

    @Test
    @DisplayName("Cross-tenant isolation: Request from different organization is denied")
    void crossTenantRequestIsDenied() throws Exception {
        UUID attackerOrg = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/pull-requests/15/changes")
                        .param("repositoryId", repo.getId().toString())
                        .header("X-Org-Id", attackerOrg.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
