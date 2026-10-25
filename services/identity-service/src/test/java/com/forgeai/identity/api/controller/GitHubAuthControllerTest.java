package com.forgeai.identity.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.api.dto.GitHubAppConfigResponse;
import com.forgeai.identity.api.dto.GitHubAuthStartResponse;
import com.forgeai.identity.api.dto.GitHubCallbackRequest;
import com.forgeai.identity.api.dto.GitHubIdentityDto;
import com.forgeai.identity.api.dto.GitHubStatusResponse;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.in.GitHubAuthenticationUseCase;
import com.forgeai.identity.infrastructure.security.RsaKeyPropertiesConfig;
import com.forgeai.identity.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GitHubAuthController.class)
@Import({SecurityConfig.class, RsaKeyPropertiesConfig.class})
class GitHubAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GitHubAuthenticationUseCase gitHubAuthenticationUseCase;

    @MockBean
    private com.forgeai.identity.infrastructure.security.JwtService jwtService;

    @Test
    @DisplayName("GET /api/v1/auth/github/config returns 200 with configuration status")
    void testGetConfig() throws Exception {
        when(gitHubAuthenticationUseCase.getConfig())
                .thenReturn(new GitHubAppConfigResponse(true, "gh-client-id-xyz"));

        mockMvc.perform(get("/api/v1/auth/github/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.clientId").value("gh-client-id-xyz"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/github/start returns 200 with authorizationUrl and state")
    void testStart() throws Exception {
        when(gitHubAuthenticationUseCase.startAuthorization(any(), any()))
                .thenReturn(new GitHubAuthStartResponse("https://github.com/login/oauth/authorize?state=xyz", "xyz"));

        mockMvc.perform(get("/api/v1/auth/github/start?redirectUri=http://localhost:3000/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://github.com/login/oauth/authorize?state=xyz"))
                .andExpect(jsonPath("$.state").value("xyz"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/github/callback returns 200 with LoginResponse")
    void testCallback() throws Exception {
        LoginResponse mockResponse = new LoginResponse(
                "forgeai-jwt-token",
                "refresh-token-val",
                "Bearer",
                900,
                new LoginResponse.UserDto(UUID.randomUUID(), "octocat", "octocat@github.com", "The", "Octocat")
        );

        when(gitHubAuthenticationUseCase.handleCallback("auth_code_123", "state_456", "http://localhost:3000/callback"))
                .thenReturn(mockResponse);

        GitHubCallbackRequest request = new GitHubCallbackRequest("auth_code_123", "state_456", "http://localhost:3000/callback");

        mockMvc.perform(post("/api/v1/auth/github/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("forgeai-jwt-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value("octocat"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/github/link returns 200 with linked GitHub identity")
    void testLink() throws Exception {
        UUID userId = UUID.randomUUID();
        GitHubIdentityDto identityDto = new GitHubIdentityDto(
                99999L,
                "octocat",
                "Octo Cat",
                "octo@github.com",
                "https://avatars.github.com/u/99999",
                "https://github.com/octocat",
                Instant.now(),
                Instant.now()
        );

        when(gitHubAuthenticationUseCase.linkAccount(eq(userId), eq("code_link"), eq("state_link"), any()))
                .thenReturn(identityDto);

        GitHubCallbackRequest request = new GitHubCallbackRequest("code_link", "state_link", "http://localhost:3000/callback");

        mockMvc.perform(post("/api/v1/auth/github/link")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubUserId").value(99999))
                .andExpect(jsonPath("$.githubLogin").value("octocat"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/github/disconnect returns 200")
    void testDisconnect() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/auth/github/disconnect")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("GitHub account unlinked successfully"));

        verify(gitHubAuthenticationUseCase).disconnectAccount(userId);
    }

    @Test
    @DisplayName("GET /api/v1/auth/github/status returns 200 with status response")
    void testGetStatus() throws Exception {
        UUID userId = UUID.randomUUID();
        GitHubStatusResponse statusResponse = new GitHubStatusResponse(
                true,
                new GitHubIdentityDto(12345L, "coder", "Coder", "coder@dev.com", null, null, Instant.now(), Instant.now())
        );

        when(gitHubAuthenticationUseCase.getStatus(userId)).thenReturn(statusResponse);

        mockMvc.perform(get("/api/v1/auth/github/status")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.identity.githubLogin").value("coder"));
    }
}
