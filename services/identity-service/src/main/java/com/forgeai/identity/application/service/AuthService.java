package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.domain.exception.AccountLockedException;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService implements AuthenticationUseCase {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenProvider tokenProvider;
    private final SessionService sessionService;
    private final SecurityEventService securityEventService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRotationService tokenRotationService;
    private final com.forgeai.identity.infrastructure.metrics.IdentityMetrics identityMetrics;
    private final com.forgeai.identity.infrastructure.logging.StructuredLogger structuredLogger;

    public AuthService(UserService userService,
                       UserRepository userRepository,
                       PasswordHasher passwordHasher,
                       TokenProvider tokenProvider,
                       SessionService sessionService,
                       SecurityEventService securityEventService,
                       RefreshTokenRepository refreshTokenRepository,
                       TokenRotationService tokenRotationService) {
        this(userService, userRepository, passwordHasher, tokenProvider, sessionService,
                securityEventService, refreshTokenRepository, tokenRotationService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserService userService,
                       UserRepository userRepository,
                       PasswordHasher passwordHasher,
                       TokenProvider tokenProvider,
                       SessionService sessionService,
                       SecurityEventService securityEventService,
                       RefreshTokenRepository refreshTokenRepository,
                       TokenRotationService tokenRotationService,
                       @org.springframework.beans.factory.annotation.Autowired(required = false) com.forgeai.identity.infrastructure.metrics.IdentityMetrics identityMetrics,
                       @org.springframework.beans.factory.annotation.Autowired(required = false) com.forgeai.identity.infrastructure.logging.StructuredLogger structuredLogger) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenProvider = tokenProvider;
        this.sessionService = sessionService;
        this.securityEventService = securityEventService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRotationService = tokenRotationService;
        this.identityMetrics = identityMetrics;
        this.structuredLogger = structuredLogger;
    }

    @Override
    @Transactional
    public User register(String email, String username, String password, String firstName, String lastName) {
        return userService.createUser(email, username, password, firstName, lastName);
    }

    @Override
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class, InvalidUserStateException.class})
    public LoginResponse login(String identifier, String password) {
        User user = resolveUser(identifier);

        String ipAddress = null;
        String userAgent = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            ipAddress = req.getRemoteAddr();
            String forwardedFor = req.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                ipAddress = forwardedFor.split(",")[0].trim();
            }
            userAgent = req.getHeader("User-Agent");
        }

        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            securityEventService.recordEvent(user.getId(), null, "LOGIN_BLOCKED_ACCOUNT_LOCKED", ipAddress, userAgent,
                    "{\"lockedUntil\":\"" + user.getLockedUntil() + "\"}");
            throw new AccountLockedException("Account is locked due to multiple failed login attempts. Please try again later.");
        }

        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            if (identityMetrics != null) {
                identityMetrics.recordLoginFailure();
            }
            if (structuredLogger != null) {
                structuredLogger.warn("security.authentication.login_failure", java.util.Map.of(
                        "identifier", identifier != null ? identifier.replaceAll("(?<=.{2}).(?=.*@)", "*") : "unknown"
                ));
            }
            if (user != null) {
                int newCount = user.getFailedLoginCount() + 1;
                user.setFailedLoginCount(newCount);
                if (newCount >= 5) {
                    Instant lockoutTime = Instant.now().plus(15, ChronoUnit.MINUTES);
                    user.setLockedUntil(lockoutTime);
                    userRepository.save(user);
                    securityEventService.recordEvent(user.getId(), null, "ACCOUNT_LOCKED_BRUTE_FORCE", ipAddress, userAgent,
                            "{\"failedAttempts\":" + newCount + ",\"lockedUntil\":\"" + lockoutTime + "\"}");
                } else {
                    userRepository.save(user);
                    securityEventService.recordEvent(user.getId(), null, "LOGIN_FAILURE", ipAddress, userAgent,
                            "{\"failedAttempts\":" + newCount + "}");
                }
            } else {
                String safeDetails = "{}";
                try {
                    safeDetails = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(java.util.Map.of("identifier", identifier != null ? identifier : ""));
                } catch (Exception ignored) {}
                securityEventService.recordEvent(null, null, "LOGIN_FAILURE", ipAddress, userAgent, safeDetails);
            }
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            securityEventService.recordEvent(user.getId(), null, "LOGIN_BLOCKED", ipAddress, userAgent, "{\"status\":\"" + user.getStatus().name() + "\"}");
            throw new InvalidUserStateException("User account is not active");
        }

        // Reset failed login count and clear lockout on successful authentication
        if (user.getFailedLoginCount() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        // Create session
        Instant sessionExpiry = Instant.now().plus(30, ChronoUnit.DAYS);
        Session session = sessionService.createSession(user.getId(), sessionExpiry, "{}");

        // Generate Refresh Token
        String rawRefreshToken = generateSecureRandomString();
        String hashedRefreshToken = hashWithSHA256(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setSessionId(session.getId());
        refreshToken.setFamilyId(UUID.randomUUID());
        refreshToken.setTokenHash(hashedRefreshToken);
        refreshToken.setIssuedAt(Instant.now());
        refreshToken.setExpiresAt(sessionExpiry);
        refreshToken.setCreatedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);

        securityEventService.recordEvent(user.getId(), null, "LOGIN_SUCCESS", ipAddress, userAgent, "{}");

        if (identityMetrics != null) {
            identityMetrics.recordLoginSuccess();
        }
        if (structuredLogger != null) {
            structuredLogger.info("security.authentication.login_success", java.util.Map.of(
                    "userId", user.getId().toString(),
                    "username", user.getUsername().value()
            ));
        }

        String token = null;
        try {
            token = tokenProvider.generateToken(user.getId(), session.getId());
        } catch (Throwable ignored) {
        }
        if (token == null) {
            token = tokenProvider.generateToken(user.getId());
        }
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

    private User resolveUser(String identifier) {
        if (identifier != null && identifier.contains("@")) {
            return userRepository.findByEmail(new Email(identifier)).orElse(null);
        } else if (identifier != null) {
            return userRepository.findByUsername(new Username(identifier)).orElse(null);
        }
        return null;
    }

    private String generateSecureRandomString() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashWithSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            throw new RuntimeException("Failed to hash refresh token", e);
        }
    }

    @Override
    @Transactional
    public com.forgeai.identity.api.dto.RefreshTokenResponse refresh(String rawRefreshToken) {
        return tokenRotationService.rotateToken(rawRefreshToken);
    }

    @Override
    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        logout(userId, rawRefreshToken, null);
    }

    @Override
    @Transactional
    public void logout(UUID userId, String rawRefreshToken, UUID sessionId) {
        boolean revoked = false;
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = hashWithSHA256(rawRefreshToken);
            var tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);
            if (tokenOpt.isPresent()) {
                Session session = sessionService.getSession(tokenOpt.get().getSessionId());
                if (session != null && session.getUserId().equals(userId)) {
                    sessionService.revokeSession(session.getId());
                    refreshTokenRepository.revokeBySessionId(session.getId());
                    revoked = true;
                }
            }
        }
        if (!revoked && sessionId != null) {
            Session session = sessionService.getSession(sessionId);
            if (session != null && session.getUserId().equals(userId)) {
                sessionService.revokeSession(session.getId());
                refreshTokenRepository.revokeBySessionId(session.getId());
            }
        }
        securityEventService.recordEvent(userId, null, "LOGOUT", null, null, "{}");
    }

    @Override
    @Transactional
    public void logoutAll(UUID userId) {
        java.util.List<Session> activeSessions = sessionService.getUserSessions(userId);
        sessionService.revokeAllUserSessions(userId);
        activeSessions.forEach(s -> refreshTokenRepository.revokeBySessionId(s.getId()));
        securityEventService.recordEvent(userId, null, "LOGOUT_ALL_SESSIONS", null, null, "{}");
    }
}
