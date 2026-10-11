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
@RequiredArgsConstructor
public class AuthService implements AuthenticationUseCase {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenProvider tokenProvider;
    private final SessionService sessionService;
    private final SecurityEventService securityEventService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenRotationService tokenRotationService;

    @Override
    @Transactional
    public User register(String email, String username, String password, String firstName, String lastName) {
        return userService.createUser(email, username, password, firstName, lastName);
    }

    @Override
    @Transactional
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
                securityEventService.recordEvent(null, null, "LOGIN_FAILURE", ipAddress, userAgent, "{\"identifier\":\"" + identifier + "\"}");
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
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = hashWithSHA256(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(refreshToken -> {
                Session session = sessionService.getSession(refreshToken.getSessionId());
                if (session != null && session.getUserId().equals(userId)) {
                    sessionService.revokeSession(session.getId());
                    refreshTokenRepository.revokeBySessionId(session.getId());
                }
            });
        }
        securityEventService.recordEvent(userId, null, "LOGOUT", null, null, "{}");
    }

    @Override
    @Transactional
    public void logoutAll(UUID userId) {
        sessionService.revokeAllUserSessions(userId);
        // Note: active refresh tokens tied to those sessions will effectively be invalidated because
        // the session is revoked. However, to explicitly revoke them:
        sessionService.getUserSessions(userId).forEach(s -> refreshTokenRepository.revokeBySessionId(s.getId()));
        securityEventService.recordEvent(userId, null, "LOGOUT_ALL_SESSIONS", null, null, "{}");
    }
}
