package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.RefreshTokenResponse;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.CompromisedTokenException;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenRotationServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private SecurityEventRepository securityEventRepository;

    @InjectMocks
    private TokenRotationService tokenRotationService;

    private User activeUser;
    private Session activeSession;
    private UUID familyId;

    @BeforeEach
    void setUp() {
        activeUser = new User();
        activeUser.setId(UUID.randomUUID());
        activeUser.setStatus(UserStatus.ACTIVE);

        activeSession = new Session();
        activeSession.setId(UUID.randomUUID());
        activeSession.setUserId(activeUser.getId());
        activeSession.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));

        familyId = UUID.randomUUID();
    }

    @Test
    void rotateToken_ValidInitialRotation_Success() {
        RefreshToken incoming = new RefreshToken();
        incoming.setId(UUID.randomUUID());
        incoming.setSessionId(activeSession.getId());
        incoming.setFamilyId(familyId);
        incoming.setTokenHash("dummy_hash");
        incoming.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(refreshTokenRepository.findByTokenHashWithLock(anyString())).thenReturn(Optional.of(incoming));
        when(sessionRepository.findById(activeSession.getId())).thenReturn(Optional.of(activeSession));
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("fresh_access_token");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);

        RefreshTokenResponse response = tokenRotationService.rotateToken("client_raw_refresh_token");

        assertNotNull(response);
        assertEquals("fresh_access_token", response.accessToken());
        assertNotNull(response.refreshToken());
        assertNotNull(incoming.getRevokedAt());
        assertNotNull(incoming.getGracePeriodUntil());
        assertNotNull(incoming.getReplacedBy());

        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        verify(sessionRepository).save(activeSession);
    }

    @Test
    void rotateToken_WithinGracePeriod_ReturnsActiveTokenWithoutRevokingFamily() {
        UUID successorId = UUID.randomUUID();

        // Token was revoked 5 seconds ago, grace_period_until is 25 seconds in future
        RefreshToken incomingRevoked = new RefreshToken();
        incomingRevoked.setId(UUID.randomUUID());
        incomingRevoked.setSessionId(activeSession.getId());
        incomingRevoked.setFamilyId(familyId);
        incomingRevoked.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        incomingRevoked.setRevokedAt(Instant.now().minus(5, ChronoUnit.SECONDS));
        incomingRevoked.setGracePeriodUntil(Instant.now().plus(25, ChronoUnit.SECONDS));
        incomingRevoked.setReplacedBy(successorId);

        RefreshToken successor = new RefreshToken();
        successor.setId(successorId);
        successor.setSessionId(activeSession.getId());
        successor.setFamilyId(familyId);
        successor.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(refreshTokenRepository.findByTokenHashWithLock(anyString())).thenReturn(Optional.of(incomingRevoked));
        when(refreshTokenRepository.findById(successorId)).thenReturn(Optional.of(successor));
        when(sessionRepository.findById(activeSession.getId())).thenReturn(Optional.of(activeSession));
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("grace_access_token");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);

        RefreshTokenResponse response = tokenRotationService.rotateToken("duplicate_concurrent_refresh_token");

        assertNotNull(response);
        assertEquals("grace_access_token", response.accessToken());

        // Assert no family or session revocation was executed
        verify(refreshTokenRepository, never()).revokeByFamilyId(any());
        verify(sessionRepository, never()).revokeById(any());
    }

    @Test
    void rotateToken_ExpiredGracePeriod_TriggersFamilyAndSessionRevocation() {
        // Token was revoked 45 seconds ago, grace period ended 15 seconds ago
        RefreshToken staleRevoked = new RefreshToken();
        staleRevoked.setId(UUID.randomUUID());
        staleRevoked.setSessionId(activeSession.getId());
        staleRevoked.setFamilyId(familyId);
        staleRevoked.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        staleRevoked.setRevokedAt(Instant.now().minus(45, ChronoUnit.SECONDS));
        staleRevoked.setGracePeriodUntil(Instant.now().minus(15, ChronoUnit.SECONDS));

        when(refreshTokenRepository.findByTokenHashWithLock(anyString())).thenReturn(Optional.of(staleRevoked));

        assertThrows(CompromisedTokenException.class, () ->
                tokenRotationService.rotateToken("compromised_replayed_token")
        );

        // Assert full family revocation and session revocation were triggered
        verify(refreshTokenRepository).revokeByFamilyId(familyId);
        verify(sessionRepository).revokeById(activeSession.getId());
        verify(securityEventRepository).save(any());
    }
}
