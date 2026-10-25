package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.GitHubAppConfigResponse;
import com.forgeai.identity.api.dto.GitHubAuthStartResponse;
import com.forgeai.identity.api.dto.GitHubIdentityDto;
import com.forgeai.identity.api.dto.GitHubStatusResponse;
import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.in.GitHubAuthenticationUseCase;
import com.forgeai.identity.application.port.out.GitHubIdentityRepository;
import com.forgeai.identity.application.port.out.GitHubOAuthClient;
import com.forgeai.identity.application.port.out.OAuthStateRepository;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.model.GitHubIdentity;
import com.forgeai.identity.domain.model.OAuthState;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.security.TokenEncryptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class GitHubAuthService implements GitHubAuthenticationUseCase {

    public static final String OAUTH_UNUSABLE_PASSWORD_PREFIX = "OAUTH_UNUSABLE$";

    private final GitHubIdentityRepository gitHubIdentityRepository;
    private final OAuthStateRepository oauthStateRepository;
    private final GitHubOAuthClient gitHubOAuthClient;
    private final TokenEncryptor tokenEncryptor;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final TokenProvider tokenProvider;
    private final SessionService sessionService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityEventService securityEventService;
    private final PasswordHasher passwordHasher;

    private final String clientId;
    private final String clientSecret;
    private final String defaultRedirectUri;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public GitHubAuthService(
            GitHubIdentityRepository gitHubIdentityRepository,
            OAuthStateRepository oauthStateRepository,
            GitHubOAuthClient gitHubOAuthClient,
            TokenEncryptor tokenEncryptor,
            UserRepository userRepository,
            @Autowired(required = false) WorkspaceService workspaceService,
            TokenProvider tokenProvider,
            SessionService sessionService,
            RefreshTokenRepository refreshTokenRepository,
            SecurityEventService securityEventService,
            PasswordHasher passwordHasher,
            @Value("${forgeai.github.client-id:${GITHUB_CLIENT_ID:}}") String clientId,
            @Value("${forgeai.github.client-secret:${GITHUB_CLIENT_SECRET:}}") String clientSecret,
            @Value("${forgeai.github.redirect-uri:${GITHUB_REDIRECT_URI:http://localhost:3000/auth/github/callback}}") String defaultRedirectUri) {
        this.gitHubIdentityRepository = gitHubIdentityRepository;
        this.oauthStateRepository = oauthStateRepository;
        this.gitHubOAuthClient = gitHubOAuthClient;
        this.tokenEncryptor = tokenEncryptor;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
        this.tokenProvider = tokenProvider;
        this.sessionService = sessionService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityEventService = securityEventService;
        this.passwordHasher = passwordHasher;
        this.clientId = clientId != null ? clientId.trim() : "";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "";
        this.defaultRedirectUri = defaultRedirectUri != null ? defaultRedirectUri.trim() : "http://localhost:3000/auth/github/callback";
    }

    @Override
    public GitHubAppConfigResponse getConfig() {
        boolean configured = !clientId.isBlank() && !clientSecret.isBlank();
        return new GitHubAppConfigResponse(configured, configured ? clientId : "");
    }

    @Override
    @Transactional
    public GitHubAuthStartResponse startAuthorization(UUID userId, String redirectUri) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("GitHub OAuth is not configured. Missing client-id or client-secret.");
        }

        String state = generateSecureToken(32);
        String finalRedirect = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : defaultRedirectUri;

        OAuthState oauthState = OAuthState.builder()
                .state(state)
                .userId(userId)
                .redirectUrl(finalRedirect)
                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                .used(false)
                .createdAt(Instant.now())
                .build();
        oauthStateRepository.save(oauthState);

        String encodedRedirect = URLEncoder.encode(finalRedirect, StandardCharsets.UTF_8);
        String authorizationUrl = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&state=%s&scope=%s",
                clientId,
                encodedRedirect,
                state,
                URLEncoder.encode("read:user user:email", StandardCharsets.UTF_8)
        );

        return new GitHubAuthStartResponse(authorizationUrl, state);
    }

    @Override
    @Transactional
    public LoginResponse handleCallback(String code, String state, String redirectUri) {
        OAuthState oauthState = validateAndConsumeState(state, null);

        String effectiveRedirect = (redirectUri != null && !redirectUri.isBlank())
                ? redirectUri
                : oauthState.getRedirectUrl();

        GitHubOAuthClient.OAuthToken token = gitHubOAuthClient.exchangeCodeForToken(code, effectiveRedirect);
        GitHubOAuthClient.UserProfile profile = gitHubOAuthClient.fetchUserProfile(token.accessToken());

        String email = resolveVerifiedEmail(profile, token.accessToken());
        String encryptedAccessToken = tokenEncryptor.encrypt(token.accessToken());

        Optional<GitHubIdentity> existingIdentityOpt = gitHubIdentityRepository.findByGithubUserId(profile.id());

        User user;
        if (existingIdentityOpt.isPresent()) {
            GitHubIdentity identity = existingIdentityOpt.get();
            user = userRepository.findById(identity.getUserId())
                    .orElseThrow(() -> new InvalidUserStateException("Associated user account not found"));

            if (user.getStatus() != UserStatus.ACTIVE) {
                securityEventService.recordEvent(user.getId(), null, "GITHUB_LOGIN_BLOCKED",
                        getClientIp(), getUserAgent(), "{\"status\":\"" + user.getStatus().name() + "\"}");
                throw new InvalidUserStateException("User account is not active");
            }

            identity.setGithubLogin(profile.login());
            if (profile.name() != null && !profile.name().isBlank()) {
                identity.setName(profile.name());
            }
            if (email != null && !email.isBlank()) {
                identity.setEmail(email);
            }
            identity.setAvatarUrl(profile.avatarUrl());
            identity.setProfileUrl(profile.htmlUrl());
            identity.setEncryptedAccessToken(encryptedAccessToken);
            identity.setTokenType(token.tokenType());
            identity.setScope(token.scope());
            identity.setUpdatedAt(Instant.now());
            identity.setLastSyncedAt(Instant.now());
            gitHubIdentityRepository.save(identity);

        } else {
            // Check if user with same verified email already exists
            Optional<User> existingUserOpt = userRepository.findByEmail(new Email(email));
            if (existingUserOpt.isPresent()) {
                user = existingUserOpt.get();
                if (gitHubIdentityRepository.existsByUserId(user.getId())) {
                    throw new IllegalStateException("Your account is already linked to another GitHub identity.");
                }

                GitHubIdentity identity = GitHubIdentity.builder()
                        .userId(user.getId())
                        .githubUserId(profile.id())
                        .githubLogin(profile.login())
                        .name(profile.name())
                        .email(email)
                        .avatarUrl(profile.avatarUrl())
                        .profileUrl(profile.htmlUrl())
                        .encryptedAccessToken(encryptedAccessToken)
                        .tokenType(token.tokenType())
                        .scope(token.scope())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .lastSyncedAt(Instant.now())
                        .build();
                gitHubIdentityRepository.save(identity);

            } else {
                // Provision new user
                String resolvedUsername = generateUniqueUsername(profile.login());
                String[] parsedNames = parseNames(profile.name(), profile.login());

                String dummySecret = "OAUTH_GITHUB_" + UUID.randomUUID() + "_" + generateSecureToken(32);
                String passwordHash = OAUTH_UNUSABLE_PASSWORD_PREFIX + passwordHasher.hash(dummySecret);

                User newUser = new User();
                newUser.setEmail(new Email(email));
                newUser.setUsername(new Username(resolvedUsername));
                newUser.setPasswordHash(passwordHash);
                newUser.setFirstName(parsedNames[0]);
                newUser.setLastName(parsedNames[1]);
                newUser.setStatus(UserStatus.ACTIVE);
                newUser.setEmailVerified(true);
                newUser.setCreatedAt(Instant.now());
                newUser.setUpdatedAt(Instant.now());
                user = userRepository.save(newUser);

                // Invariant: provision Personal Workspace for the user
                if (workspaceService != null) {
                    try {
                        workspaceService.getOrCreatePersonalWorkspace(user.getId(), user.getUsername().value());
                    } catch (Exception e) {
                        log.warn("Personal workspace provisioning warning for user {}: {}", user.getId(), e.getMessage());
                    }
                }

                GitHubIdentity identity = GitHubIdentity.builder()
                        .userId(user.getId())
                        .githubUserId(profile.id())
                        .githubLogin(profile.login())
                        .name(profile.name())
                        .email(email)
                        .avatarUrl(profile.avatarUrl())
                        .profileUrl(profile.htmlUrl())
                        .encryptedAccessToken(encryptedAccessToken)
                        .tokenType(token.tokenType())
                        .scope(token.scope())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .lastSyncedAt(Instant.now())
                        .build();
                gitHubIdentityRepository.save(identity);

                securityEventService.recordEvent(user.getId(), null, "USER_REGISTERED_VIA_GITHUB",
                        getClientIp(), getUserAgent(), "{\"githubLogin\":\"" + profile.login() + "\"}");
            }
        }

        securityEventService.recordEvent(user.getId(), null, "GITHUB_LOGIN_SUCCESS",
                getClientIp(), getUserAgent(), "{\"githubLogin\":\"" + profile.login() + "\"}");

        return createSessionAndTokens(user);
    }

    @Override
    @Transactional
    public GitHubIdentityDto linkAccount(UUID userId, String code, String state, String redirectUri) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to link GitHub account");
        }

        OAuthState oauthState = validateAndConsumeState(state, userId);

        String effectiveRedirect = (redirectUri != null && !redirectUri.isBlank())
                ? redirectUri
                : oauthState.getRedirectUrl();

        GitHubOAuthClient.OAuthToken token = gitHubOAuthClient.exchangeCodeForToken(code, effectiveRedirect);
        GitHubOAuthClient.UserProfile profile = gitHubOAuthClient.fetchUserProfile(token.accessToken());

        Optional<GitHubIdentity> existingOpt = gitHubIdentityRepository.findByGithubUserId(profile.id());
        if (existingOpt.isPresent() && !existingOpt.get().getUserId().equals(userId)) {
            throw new IllegalStateException("This GitHub account is already linked to another ForgeAI user.");
        }

        String email = resolveVerifiedEmail(profile, token.accessToken());
        String encryptedAccessToken = tokenEncryptor.encrypt(token.accessToken());

        Optional<GitHubIdentity> currentOpt = gitHubIdentityRepository.findByUserId(userId);
        GitHubIdentity identity;
        if (currentOpt.isPresent()) {
            identity = currentOpt.get();
            identity.setGithubUserId(profile.id());
            identity.setGithubLogin(profile.login());
            identity.setName(profile.name());
            identity.setEmail(email);
            identity.setAvatarUrl(profile.avatarUrl());
            identity.setProfileUrl(profile.htmlUrl());
            identity.setEncryptedAccessToken(encryptedAccessToken);
            identity.setTokenType(token.tokenType());
            identity.setScope(token.scope());
            identity.setUpdatedAt(Instant.now());
            identity.setLastSyncedAt(Instant.now());
        } else {
            identity = GitHubIdentity.builder()
                    .userId(userId)
                    .githubUserId(profile.id())
                    .githubLogin(profile.login())
                    .name(profile.name())
                    .email(email)
                    .avatarUrl(profile.avatarUrl())
                    .profileUrl(profile.htmlUrl())
                    .encryptedAccessToken(encryptedAccessToken)
                    .tokenType(token.tokenType())
                    .scope(token.scope())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .lastSyncedAt(Instant.now())
                    .build();
        }

        GitHubIdentity saved = gitHubIdentityRepository.save(identity);
        securityEventService.recordEvent(userId, null, "GITHUB_ACCOUNT_LINKED",
                getClientIp(), getUserAgent(), "{\"githubLogin\":\"" + profile.login() + "\"}");

        return toDto(saved);
    }

    @Override
    @Transactional
    public void disconnectAccount(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to disconnect GitHub account");
        }

        Optional<GitHubIdentity> identityOpt = gitHubIdentityRepository.findByUserId(userId);
        if (identityOpt.isEmpty()) {
            return; // Idempotent
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        // Lockout protection: ensure user has an established password before disconnecting GitHub
        if (user.getPasswordHash() != null && user.getPasswordHash().startsWith(OAUTH_UNUSABLE_PASSWORD_PREFIX)) {
            throw new IllegalStateException(
                    "Cannot disconnect GitHub: You must set an account password in Settings before disconnecting GitHub to ensure you are not locked out.");
        }

        gitHubIdentityRepository.deleteByUserId(userId);
        securityEventService.recordEvent(userId, null, "GITHUB_ACCOUNT_DISCONNECTED",
                getClientIp(), getUserAgent(), "{}");
    }

    @Override
    @Transactional(readOnly = true)
    public GitHubStatusResponse getStatus(UUID userId) {
        if (userId == null) {
            return new GitHubStatusResponse(false, null);
        }
        return gitHubIdentityRepository.findByUserId(userId)
                .map(identity -> new GitHubStatusResponse(true, toDto(identity)))
                .orElseGet(() -> new GitHubStatusResponse(false, null));
    }

    private OAuthState validateAndConsumeState(String state, UUID expectedUserId) {
        if (state == null || state.isBlank()) {
            throw new InvalidCredentialsException("OAuth state is required");
        }

        OAuthState oauthState = oauthStateRepository.findByState(state)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or unrecognized OAuth state"));

        if (oauthState.isUsed()) {
            throw new InvalidCredentialsException("OAuth state has already been used. Please try again.");
        }

        if (oauthState.isExpired()) {
            throw new InvalidCredentialsException("OAuth state has expired. Please try again.");
        }

        if (expectedUserId != null && !Objects.equals(oauthState.getUserId(), expectedUserId)) {
            throw new InvalidCredentialsException("OAuth state does not match authenticated user session.");
        }

        oauthState.setUsed(true);
        return oauthStateRepository.save(oauthState);
    }

    private String resolveVerifiedEmail(GitHubOAuthClient.UserProfile profile, String accessToken) {
        if (profile.email() != null && !profile.email().isBlank()) {
            return profile.email().trim().toLowerCase();
        }

        List<GitHubOAuthClient.UserEmail> emails = gitHubOAuthClient.fetchUserEmails(accessToken);
        // Look for primary and verified email
        Optional<String> primaryVerified = emails.stream()
                .filter(e -> e.primary() && e.verified() && e.email() != null && !e.email().isBlank())
                .map(e -> e.email().trim().toLowerCase())
                .findFirst();

        if (primaryVerified.isPresent()) {
            return primaryVerified.get();
        }

        // Look for any verified email
        Optional<String> anyVerified = emails.stream()
                .filter(e -> e.verified() && e.email() != null && !e.email().isBlank())
                .map(e -> e.email().trim().toLowerCase())
                .findFirst();

        if (anyVerified.isPresent()) {
            return anyVerified.get();
        }

        // Fallback to GitHub noreply address
        return profile.login().toLowerCase() + "@users.noreply.github.com";
    }

    private String generateUniqueUsername(String candidateLogin) {
        String base = candidateLogin.replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();
        if (base.length() < 3) {
            base = "gh_" + base;
        }
        if (base.length() > 25) {
            base = base.substring(0, 25);
        }

        String username = base;
        int counter = 1;
        while (userRepository.findByUsername(new Username(username)).isPresent()) {
            username = base + "_" + counter;
            counter++;
        }
        return username;
    }

    private String[] parseNames(String fullName, String login) {
        if (fullName == null || fullName.isBlank()) {
            return new String[]{login, ""};
        }
        String[] parts = fullName.trim().split("\\s+", 2);
        String firstName = parts[0];
        String lastName = parts.length > 1 ? parts[1] : "";
        return new String[]{firstName, lastName};
    }

    private LoginResponse createSessionAndTokens(User user) {
        Instant sessionExpiry = Instant.now().plus(30, ChronoUnit.DAYS);
        Session session = sessionService.createSession(user.getId(), sessionExpiry, "{}");

        String rawRefreshToken = generateSecureToken(32);
        String hashedRefreshToken = hashWithSHA256(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setSessionId(session.getId());
        refreshToken.setFamilyId(UUID.randomUUID());
        refreshToken.setTokenHash(hashedRefreshToken);
        refreshToken.setIssuedAt(Instant.now());
        refreshToken.setExpiresAt(sessionExpiry);
        refreshToken.setCreatedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);

        String token = tokenProvider.generateToken(user.getId());

        return new LoginResponse(
                token,
                rawRefreshToken,
                "Bearer",
                tokenProvider.getExpirationSeconds(),
                new LoginResponse.UserDto(
                        user.getId(),
                        user.getUsername().value(),
                        user.getEmail().value(),
                        user.getFirstName(),
                        user.getLastName()
                )
        );
    }

    private GitHubIdentityDto toDto(GitHubIdentity identity) {
        if (identity == null) {
            return null;
        }
        return new GitHubIdentityDto(
                identity.getGithubUserId(),
                identity.getGithubLogin(),
                identity.getName(),
                identity.getEmail(),
                identity.getAvatarUrl(),
                identity.getProfileUrl(),
                identity.getCreatedAt(),
                identity.getLastSyncedAt()
        );
    }

    private String generateSecureToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashWithSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash token", e);
        }
    }

    private String getClientIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            String forwardedFor = req.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        }
        return null;
    }

    private String getUserAgent() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getHeader("User-Agent");
        }
        return null;
    }
}
