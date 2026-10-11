package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.api.dto.RefreshTokenResponse;
import com.forgeai.identity.domain.exception.CompromisedTokenException;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.model.SecurityEvent;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRotationService {

    private static final Duration GRACE_PERIOD = Duration.ofSeconds(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final TokenProvider tokenProvider;
    private final SecurityEventRepository securityEventRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefreshTokenResponse rotateToken(String rawRefreshToken) {
        String tokenHash = hashWithSHA256(rawRefreshToken);

        // 1. Query incoming hashed refresh token using pessimistic write lock
        RefreshToken incomingToken = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));

        Instant now = Instant.now();

        // 2. Validate token expiration
        if (incomingToken.isExpired()) {
            throw new InvalidCredentialsException("Refresh token has expired");
        }

        // Case A / Case B: Token is already revoked
        if (incomingToken.isRevoked()) {
            // Case A: Within grace window
            if (incomingToken.getGracePeriodUntil() != null && now.isBefore(incomingToken.getGracePeriodUntil())) {
                log.info("Concurrent refresh detected within grace window for token family {}. Returning active generation.",
                        incomingToken.getFamilyId());

                UUID successorId = incomingToken.getReplacedBy();
                if (successorId == null) {
                    throw new InvalidCredentialsException("Token replacement lineage unavailable");
                }

                RefreshToken successorToken = refreshTokenRepository.findById(successorId)
                        .orElseThrow(() -> new InvalidCredentialsException("Successor refresh token not found"));

                // Verify session
                Session session = validateActiveSession(successorToken.getSessionId(), now);
                validateActiveUser(session.getUserId());

                // Issue fresh access token without revoking the family
                String freshAccessToken = tokenProvider.generateToken(session.getUserId());

                // Return fresh access token and null or successor token identifier
                return new RefreshTokenResponse(
                        freshAccessToken,
                        null, // client reuses previously rotated token or continues with new access token
                        "Bearer",
                        tokenProvider.getExpirationSeconds()
                );
            }

            // Case B: Replay Attack (revoked and past grace period)
            log.error("SECURITY ALERT: Compromised refresh token replay detected for family: {}, session: {}",
                    incomingToken.getFamilyId(), incomingToken.getSessionId());

            // Revoke all tokens in the family_id and revoke associated session
            refreshTokenRepository.revokeByFamilyId(incomingToken.getFamilyId());
            sessionRepository.revokeById(incomingToken.getSessionId());

            recordSecurityEvent(null, "COMPROMISED_TOKEN_REPLAY_DETECTED",
                    "{\"familyId\":\"" + incomingToken.getFamilyId() + "\",\"sessionId\":\"" + incomingToken.getSessionId() + "\"}");

            throw new CompromisedTokenException("Refresh token reuse detected. All sessions in this family have been terminated.");
        }

        // Case C: Valid Rotation (Token is not revoked)
        Session session = validateActiveSession(incomingToken.getSessionId(), now);
        validateActiveUser(session.getUserId());

        // Mark incoming token revoked with grace_period_until = now + 30s
        incomingToken.setRevokedAt(now);
        incomingToken.setGracePeriodUntil(now.plus(GRACE_PERIOD));

        // Generate cryptographically secure random token for next generation
        String newRawRefreshToken = generateSecureRandomString();
        String newHashedRefreshToken = hashWithSHA256(newRawRefreshToken);

        RefreshToken nextToken = new RefreshToken();
        nextToken.setSessionId(session.getId());
        nextToken.setFamilyId(incomingToken.getFamilyId()); // Same family_id
        nextToken.setTokenHash(newHashedRefreshToken);
        nextToken.setIssuedAt(now);
        nextToken.setExpiresAt(incomingToken.getExpiresAt());
        nextToken.setCreatedAt(now);

        RefreshToken savedNextToken = refreshTokenRepository.save(nextToken);

        // Set previous token replaced_by pointer
        incomingToken.setReplacedBy(savedNextToken.getId());
        refreshTokenRepository.save(incomingToken);

        // Touch session last used
        session.setLastUsedAt(now);
        sessionRepository.save(session);

        recordSecurityEvent(session.getUserId(), "REFRESH_SUCCESS",
                "{\"familyId\":\"" + incomingToken.getFamilyId() + "\"}");

        String accessToken = tokenProvider.generateToken(session.getUserId());

        return new RefreshTokenResponse(
                accessToken,
                newRawRefreshToken,
                "Bearer",
                tokenProvider.getExpirationSeconds()
        );
    }

    private Session validateActiveSession(UUID sessionId, Instant now) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new InvalidCredentialsException("Session not found"));

        if (session.getRevokedAt() != null || session.getExpiresAt().isBefore(now)) {
            throw new InvalidCredentialsException("Session is no longer active");
        }
        return session;
    }

    private void validateActiveUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("User account is not active");
        }
    }

    private String generateSecureRandomString() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashWithSHA256(String input) {
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
            throw new IllegalStateException("Failed to hash token with SHA-256", e);
        }
    }

    private void recordSecurityEvent(UUID userId, String eventType, String details) {
        try {
            SecurityEvent event = new SecurityEvent();
            event.setUserId(userId);
            event.setEventType(eventType);
            event.setDetails(details);
            event.setCreatedAt(Instant.now());
            securityEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to record security event {}", eventType, e);
        }
    }
}
