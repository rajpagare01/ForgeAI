package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.GitHubAppConfigResponse;
import com.forgeai.identity.api.dto.GitHubAuthStartResponse;
import com.forgeai.identity.api.dto.GitHubIdentityDto;
import com.forgeai.identity.api.dto.GitHubStatusResponse;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.out.GitHubIdentityRepository;
import com.forgeai.identity.application.port.out.GitHubOAuthClient;
import com.forgeai.identity.application.port.out.OAuthStateRepository;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.model.GitHubIdentity;
import com.forgeai.identity.domain.model.OAuthState;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.model.Workspace;
import com.forgeai.identity.domain.model.WorkspaceType;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.security.TokenEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubAuthServiceTest {

    @Mock
    private GitHubIdentityRepository gitHubIdentityRepository;
    @Mock
    private OAuthStateRepository oauthStateRepository;
    @Mock
    private GitHubOAuthClient gitHubOAuthClient;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private SessionService sessionService;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private SecurityEventService securityEventService;
    @Mock
    private PasswordHasher passwordHasher;

    private TokenEncryptor tokenEncryptor;
    private GitHubAuthService gitHubAuthService;

    private static final String CLIENT_ID = "gh-test-client-id";
    private static final String CLIENT_SECRET = "gh-test-client-secret";
    private static final String REDIRECT_URI = "http://localhost:3000/auth/github/callback";
    private static final String ENCRYPTION_KEY = "test-encryption-key-for-unit-tests-32";

    @BeforeEach
    void setUp() {
        tokenEncryptor = new TokenEncryptor(ENCRYPTION_KEY);
        gitHubAuthService = new GitHubAuthService(
                gitHubIdentityRepository,
                oauthStateRepository,
                gitHubOAuthClient,
                tokenEncryptor,
                userRepository,
                workspaceService,
                tokenProvider,
                sessionService,
                refreshTokenRepository,
                securityEventService,
                passwordHasher,
                CLIENT_ID,
                CLIENT_SECRET,
                REDIRECT_URI
        );
    }

    @Test
    @DisplayName("getConfig returns configured=true and clientId when credentials are present")
    void testGetConfig_Configured() {
        GitHubAppConfigResponse config = gitHubAuthService.getConfig();
        assertTrue(config.configured());
        assertEquals(CLIENT_ID, config.clientId());
    }

    @Test
    @DisplayName("getConfig returns configured=false when credentials are blank")
    void testGetConfig_Unconfigured() {
        GitHubAuthService unconfigured = new GitHubAuthService(
                gitHubIdentityRepository, oauthStateRepository, gitHubOAuthClient,
                tokenEncryptor, userRepository, workspaceService, tokenProvider,
                sessionService, refreshTokenRepository, securityEventService, passwordHasher,
                "", "", REDIRECT_URI
        );
        GitHubAppConfigResponse config = unconfigured.getConfig();
        assertFalse(config.configured());
        assertEquals("", config.clientId());
    }

    @Test
    @DisplayName("startAuthorization generates secure state and GitHub authorization URL")
    void testStartAuthorization_Success() {
        when(oauthStateRepository.save(any(OAuthState.class))).thenAnswer(i -> i.getArgument(0));

        GitHubAuthStartResponse response = gitHubAuthService.startAuthorization(null, REDIRECT_URI);

        assertNotNull(response);
        assertNotNull(response.state());
        assertFalse(response.state().isBlank());
        assertTrue(response.authorizationUrl().contains("client_id=" + CLIENT_ID));
        assertTrue(response.authorizationUrl().contains("state=" + response.state()));
        assertTrue(response.authorizationUrl().contains("read%3Auser+user%3Aemail"));

        ArgumentCaptor<OAuthState> captor = ArgumentCaptor.forClass(OAuthState.class);
        verify(oauthStateRepository).save(captor.capture());
        OAuthState saved = captor.getValue();
        assertEquals(response.state(), saved.getState());
        assertNull(saved.getUserId());
        assertFalse(saved.isUsed());
        assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    @DisplayName("startAuthorization throws IllegalStateException when not configured")
    void testStartAuthorization_NotConfigured() {
        GitHubAuthService unconfigured = new GitHubAuthService(
                gitHubIdentityRepository, oauthStateRepository, gitHubOAuthClient,
                tokenEncryptor, userRepository, workspaceService, tokenProvider,
                sessionService, refreshTokenRepository, securityEventService, passwordHasher,
                "", "", REDIRECT_URI
        );
        assertThrows(IllegalStateException.class, () -> unconfigured.startAuthorization(null, REDIRECT_URI));
    }

    @Test
    @DisplayName("handleCallback throws InvalidCredentialsException when state is not found")
    void testHandleCallback_StateNotFound() {
        when(oauthStateRepository.findByState("missing-state")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () ->
                gitHubAuthService.handleCallback("test-code", "missing-state", REDIRECT_URI));
    }

    @Test
    @DisplayName("handleCallback prevents replay attacks when state was already used")
    void testHandleCallback_ReplayAttackPrevented() {
        OAuthState usedState = OAuthState.builder()
                .state("used-state")
                .used(true)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
        when(oauthStateRepository.findByState("used-state")).thenReturn(Optional.of(usedState));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () ->
                gitHubAuthService.handleCallback("test-code", "used-state", REDIRECT_URI));
        assertTrue(ex.getMessage().contains("already been used"));
    }

    @Test
    @DisplayName("handleCallback throws InvalidCredentialsException when state is expired")
    void testHandleCallback_ExpiredState() {
        OAuthState expiredState = OAuthState.builder()
                .state("expired-state")
                .used(false)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build();
        when(oauthStateRepository.findByState("expired-state")).thenReturn(Optional.of(expiredState));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () ->
                gitHubAuthService.handleCallback("test-code", "expired-state", REDIRECT_URI));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("handleCallback creates new user and personal workspace when GitHub user is new")
    void testHandleCallback_NewUser_CreatesPersonalWorkspace() {
        String state = "valid-state";
        OAuthState oauthState = OAuthState.builder()
                .state(state)
                .redirectUrl(REDIRECT_URI)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .used(false)
                .build();
        when(oauthStateRepository.findByState(state)).thenReturn(Optional.of(oauthState));
        when(oauthStateRepository.save(any(OAuthState.class))).thenAnswer(i -> i.getArgument(0));

        when(gitHubOAuthClient.exchangeCodeForToken("code123", REDIRECT_URI))
                .thenReturn(new GitHubOAuthClient.OAuthToken("gho_rawtoken123", "bearer", "read:user user:email"));

        when(gitHubOAuthClient.fetchUserProfile("gho_rawtoken123"))
                .thenReturn(new GitHubOAuthClient.UserProfile(
                        98765L,
                        "octocat",
                        "The Octocat",
                        "octocat@github.com",
                        "https://avatars.githubusercontent.com/u/98765",
                        "https://github.com/octocat"
                ));

        when(gitHubIdentityRepository.findByGithubUserId(98765L)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any(Username.class))).thenReturn(Optional.empty());
        when(passwordHasher.hash(anyString())).thenReturn("hashed-dummy-pwd");

        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        Session mockSession = new Session();
        mockSession.setId(UUID.randomUUID());
        when(sessionService.createSession(any(UUID.class), any(Instant.class), anyString())).thenReturn(mockSession);
        when(tokenProvider.generateToken(any(UUID.class))).thenReturn("jwt-forgeai-token");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);

        LoginResponse response = gitHubAuthService.handleCallback("code123", state, REDIRECT_URI);

        assertNotNull(response);
        assertEquals("jwt-forgeai-token", response.accessToken());
        assertEquals("octocat", response.user().username());
        assertEquals("octocat@github.com", response.user().email());

        // Invariant: verify state is consumed
        assertTrue(oauthState.isUsed());

        // Invariant: verify personal workspace creation was invoked
        verify(workspaceService).getOrCreatePersonalWorkspace(any(UUID.class), eq("octocat"));

        // Invariant: verify GitHub identity was saved with encrypted token
        ArgumentCaptor<GitHubIdentity> identityCaptor = ArgumentCaptor.forClass(GitHubIdentity.class);
        verify(gitHubIdentityRepository).save(identityCaptor.capture());
        GitHubIdentity savedIdentity = identityCaptor.getValue();
        assertEquals(98765L, savedIdentity.getGithubUserId());
        assertEquals("octocat", savedIdentity.getGithubLogin());
        assertNotNull(savedIdentity.getEncryptedAccessToken());
        assertNotEquals("gho_rawtoken123", savedIdentity.getEncryptedAccessToken());
        assertEquals("gho_rawtoken123", tokenEncryptor.decrypt(savedIdentity.getEncryptedAccessToken()));

        // Invariant: audit event recorded
        verify(securityEventService).recordEvent(any(UUID.class), isNull(), eq("GITHUB_LOGIN_SUCCESS"), any(), any(), anyString());
    }

    @Test
    @DisplayName("handleCallback logs in existing linked user and refreshes GitHub profile")
    void testHandleCallback_ExistingLinkedUser() {
        String state = "valid-state-existing";
        OAuthState oauthState = OAuthState.builder()
                .state(state)
                .redirectUrl(REDIRECT_URI)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .used(false)
                .build();
        when(oauthStateRepository.findByState(state)).thenReturn(Optional.of(oauthState));
        when(oauthStateRepository.save(any(OAuthState.class))).thenAnswer(i -> i.getArgument(0));

        when(gitHubOAuthClient.exchangeCodeForToken("code456", REDIRECT_URI))
                .thenReturn(new GitHubOAuthClient.OAuthToken("gho_newtoken456", "bearer", "read:user"));

        when(gitHubOAuthClient.fetchUserProfile("gho_newtoken456"))
                .thenReturn(new GitHubOAuthClient.UserProfile(
                        98765L,
                        "octocat_updated",
                        "Octocat Renamed",
                        "octocat@github.com",
                        "https://avatars.githubusercontent.com/u/98765",
                        "https://github.com/octocat"
                ));

        UUID existingUserId = UUID.randomUUID();
        GitHubIdentity existingIdentity = GitHubIdentity.builder()
                .id(UUID.randomUUID())
                .userId(existingUserId)
                .githubUserId(98765L)
                .githubLogin("octocat")
                .name("The Octocat")
                .email("octocat@github.com")
                .build();
        when(gitHubIdentityRepository.findByGithubUserId(98765L)).thenReturn(Optional.of(existingIdentity));

        User existingUser = new User();
        existingUser.setId(existingUserId);
        existingUser.setUsername(new Username("octocat"));
        existingUser.setEmail(new Email("octocat@github.com"));
        existingUser.setFirstName("Octocat");
        existingUser.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(existingUserId)).thenReturn(Optional.of(existingUser));

        Session mockSession = new Session();
        mockSession.setId(UUID.randomUUID());
        when(sessionService.createSession(any(UUID.class), any(Instant.class), anyString())).thenReturn(mockSession);
        when(tokenProvider.generateToken(existingUserId)).thenReturn("jwt-session-token");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);

        LoginResponse response = gitHubAuthService.handleCallback("code456", state, REDIRECT_URI);

        assertNotNull(response);
        assertEquals("jwt-session-token", response.accessToken());
        assertEquals("octocat", response.user().username());

        // Verify updated login and encrypted token were saved
        verify(gitHubIdentityRepository).save(existingIdentity);
        assertEquals("octocat_updated", existingIdentity.getGithubLogin());
        assertEquals("gho_newtoken456", tokenEncryptor.decrypt(existingIdentity.getEncryptedAccessToken()));
    }

    @Test
    @DisplayName("handleCallback blocks login when user status is not ACTIVE")
    void testHandleCallback_SuspendedUserBlocked() {
        String state = "valid-state-suspended";
        OAuthState oauthState = OAuthState.builder()
                .state(state)
                .redirectUrl(REDIRECT_URI)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .used(false)
                .build();
        when(oauthStateRepository.findByState(state)).thenReturn(Optional.of(oauthState));
        when(oauthStateRepository.save(any(OAuthState.class))).thenAnswer(i -> i.getArgument(0));

        when(gitHubOAuthClient.exchangeCodeForToken("code-suspended", REDIRECT_URI))
                .thenReturn(new GitHubOAuthClient.OAuthToken("gho_tok", "bearer", "read:user"));
        when(gitHubOAuthClient.fetchUserProfile("gho_tok"))
                .thenReturn(new GitHubOAuthClient.UserProfile(111L, "suspended_dev", "Dev", "dev@example.com", null, null));

        UUID userId = UUID.randomUUID();
        GitHubIdentity existingIdentity = GitHubIdentity.builder()
                .userId(userId)
                .githubUserId(111L)
                .build();
        when(gitHubIdentityRepository.findByGithubUserId(111L)).thenReturn(Optional.of(existingIdentity));

        User suspendedUser = new User();
        suspendedUser.setId(userId);
        suspendedUser.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(suspendedUser));

        assertThrows(InvalidUserStateException.class, () ->
                gitHubAuthService.handleCallback("code-suspended", state, REDIRECT_URI));

        verify(securityEventService).recordEvent(eq(userId), isNull(), eq("GITHUB_LOGIN_BLOCKED"), any(), any(), anyString());
    }

    @Test
    @DisplayName("linkAccount rejects state when user ID does not match state owner")
    void testLinkAccount_UserMismatchRejected() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        OAuthState oauthState = OAuthState.builder()
                .state("link-state")
                .userId(userA)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .used(false)
                .build();
        when(oauthStateRepository.findByState("link-state")).thenReturn(Optional.of(oauthState));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () ->
                gitHubAuthService.linkAccount(userB, "code", "link-state", REDIRECT_URI));
        assertTrue(ex.getMessage().contains("user session"));
    }

    @Test
    @DisplayName("linkAccount prevents linking a GitHub identity already linked to another user")
    void testLinkAccount_DuplicateGitHubIdentityPrevented() {
        UUID currentUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        OAuthState oauthState = OAuthState.builder()
                .state("link-state")
                .userId(currentUser)
                .redirectUrl(REDIRECT_URI)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .used(false)
                .build();
        when(oauthStateRepository.findByState("link-state")).thenReturn(Optional.of(oauthState));
        when(oauthStateRepository.save(any(OAuthState.class))).thenAnswer(i -> i.getArgument(0));

        when(gitHubOAuthClient.exchangeCodeForToken("code", REDIRECT_URI))
                .thenReturn(new GitHubOAuthClient.OAuthToken("gho_token", "bearer", "read:user"));
        when(gitHubOAuthClient.fetchUserProfile("gho_token"))
                .thenReturn(new GitHubOAuthClient.UserProfile(55555L, "other_login", "Name", "test@test.com", null, null));

        GitHubIdentity otherUserIdentity = GitHubIdentity.builder()
                .userId(otherUser)
                .githubUserId(55555L)
                .build();
        when(gitHubIdentityRepository.findByGithubUserId(55555L)).thenReturn(Optional.of(otherUserIdentity));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                gitHubAuthService.linkAccount(currentUser, "code", "link-state", REDIRECT_URI));
        assertTrue(ex.getMessage().contains("already linked to another ForgeAI user"));
    }

    @Test
    @DisplayName("disconnectAccount unlinks GitHub account safely")
    void testDisconnectAccount_Success() {
        UUID userId = UUID.randomUUID();
        GitHubIdentity identity = GitHubIdentity.builder()
                .userId(userId)
                .githubUserId(1234L)
                .build();
        when(gitHubIdentityRepository.findByUserId(userId)).thenReturn(Optional.of(identity));

        User user = new User();
        user.setId(userId);
        user.setPasswordHash("$2a$10$real-user-password-hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        gitHubAuthService.disconnectAccount(userId);

        verify(gitHubIdentityRepository).deleteByUserId(userId);
        verify(securityEventService).recordEvent(eq(userId), isNull(), eq("GITHUB_ACCOUNT_DISCONNECTED"), any(), any(), anyString());
    }

    @Test
    @DisplayName("disconnectAccount prevents lockout when user only has OAuth unusable dummy password")
    void testDisconnectAccount_LockoutProtection() {
        UUID userId = UUID.randomUUID();
        GitHubIdentity identity = GitHubIdentity.builder()
                .userId(userId)
                .githubUserId(1234L)
                .build();
        when(gitHubIdentityRepository.findByUserId(userId)).thenReturn(Optional.of(identity));

        User user = new User();
        user.setId(userId);
        user.setPasswordHash(GitHubAuthService.OAUTH_UNUSABLE_PASSWORD_PREFIX + "dummy-hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                gitHubAuthService.disconnectAccount(userId));
        assertTrue(ex.getMessage().contains("must set an account password"));
        verify(gitHubIdentityRepository, never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("getStatus returns linked=true and sanitized DTO without sensitive tokens")
    void testGetStatus_Linked() {
        UUID userId = UUID.randomUUID();
        GitHubIdentity identity = GitHubIdentity.builder()
                .userId(userId)
                .githubUserId(999L)
                .githubLogin("mona")
                .name("Mona Lisa")
                .email("mona@github.com")
                .avatarUrl("https://avatars.github.com/mona")
                .profileUrl("https://github.com/mona")
                .encryptedAccessToken("super-secret-encrypted-payload")
                .createdAt(Instant.now())
                .lastSyncedAt(Instant.now())
                .build();
        when(gitHubIdentityRepository.findByUserId(userId)).thenReturn(Optional.of(identity));

        GitHubStatusResponse response = gitHubAuthService.getStatus(userId);

        assertTrue(response.linked());
        assertNotNull(response.identity());
        assertEquals("mona", response.identity().githubLogin());
        assertEquals(999L, response.identity().githubUserId());
        assertEquals("Mona Lisa", response.identity().name());
    }
}
