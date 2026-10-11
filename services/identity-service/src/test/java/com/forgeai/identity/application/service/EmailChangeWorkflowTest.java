package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.event.EmailVerificationRequestedEvent;
import com.forgeai.identity.domain.event.UserUpdatedEvent;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.EmailVerificationTokenJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.EmailVerificationTokenRepository;
import com.forgeai.identity.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailChangeWorkflowTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionService sessionService;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                sessionService,
                securityEventService,
                passwordHasher,
                refreshTokenRepository,
                outboxService,
                emailVerificationTokenRepository
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail(new Email("original@example.com"));
        testUser.setUsername(new Username("testuser"));
        testUser.setStatus(UserStatus.ACTIVE);
        testUser.setEmailVerified(false);
    }

    @Test
    @DisplayName("Email Request: Sets pending_email, creates 64-character token hash, does NOT alter active email")
    void shouldRequestEmailChangeWithoutAlteringActiveEmail() {
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(userRepository.findByEmail(new Email("newemail@example.com"))).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updatedUser = userService.requestEmailChange(testUser.getId(), "newemail@example.com");

        // Assert login email is untouched
        assertThat(updatedUser.getEmail().value()).isEqualTo("original@example.com");
        assertThat(updatedUser.getPendingEmail().value()).isEqualTo("newemail@example.com");

        // Assert token persisted with 24-hour expiration
        ArgumentCaptor<EmailVerificationTokenJpaEntity> tokenCaptor =
                ArgumentCaptor.forClass(EmailVerificationTokenJpaEntity.class);
        verify(emailVerificationTokenRepository).save(tokenCaptor.capture());
        EmailVerificationTokenJpaEntity savedToken = tokenCaptor.getValue();

        assertThat(savedToken.getUserId()).isEqualTo(testUser.getId());
        assertThat(savedToken.getNewEmail()).isEqualTo("newemail@example.com");
        assertThat(savedToken.getTokenHash()).hasSize(64); // SHA-256 hex string length
        assertThat(savedToken.getExpiresAt()).isAfter(Instant.now().plus(23, ChronoUnit.HOURS));

        // Assert outbox event was published
        verify(outboxService).publish(any(EmailVerificationRequestedEvent.class), isNull());
    }

    @Test
    @DisplayName("Email Verify: Swaps email = pending_email, marks verified, clears pending, and publishes UserUpdatedEvent")
    void shouldVerifyAndSwapEmail() {
        testUser.setPendingEmail(new Email("verified@example.com"));

        // Capture raw token from request
        String rawToken = "mySecretVerificationToken64CharsLongString123456789012345678901234";
        // Calculate hash
        String tokenHash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            tokenHash = sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        EmailVerificationTokenJpaEntity tokenEntity = new EmailVerificationTokenJpaEntity();
        tokenEntity.setId(UUID.randomUUID());
        tokenEntity.setUserId(testUser.getId());
        tokenEntity.setNewEmail("verified@example.com");
        tokenEntity.setTokenHash(tokenHash);
        tokenEntity.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        tokenEntity.setCreatedAt(Instant.now());

        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(emailVerificationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(tokenEntity));
        when(userRepository.findByEmail(new Email("verified@example.com"))).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User finalUser = userService.verifyEmailChange(testUser.getId(), rawToken);

        assertThat(finalUser.getEmail().value()).isEqualTo("verified@example.com");
        assertThat(finalUser.getPendingEmail()).isNull();
        assertThat(finalUser.isEmailVerified()).isTrue();
        assertThat(tokenEntity.getUsedAt()).isNotNull();

        verify(emailVerificationTokenRepository).save(tokenEntity);
        verify(outboxService).publish(any(UserUpdatedEvent.class), isNull());
    }

    @Test
    @DisplayName("Email Verify: Expired token is rejected with IllegalArgumentException")
    void shouldRejectExpiredVerificationToken() {
        String rawToken = "expiredToken";
        String tokenHash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            tokenHash = sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        EmailVerificationTokenJpaEntity expiredToken = new EmailVerificationTokenJpaEntity();
        expiredToken.setUserId(testUser.getId());
        expiredToken.setNewEmail("expired@example.com");
        expiredToken.setTokenHash(tokenHash);
        expiredToken.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)); // Expired

        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(emailVerificationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(expiredToken));

        assertThrows(IllegalArgumentException.class, () ->
                userService.verifyEmailChange(testUser.getId(), rawToken)
        );
    }
}
