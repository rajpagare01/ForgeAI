package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.AccountLockedException;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLockoutTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private SessionService sessionService;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenRotationService tokenRotationService;

    @Mock
    private UserService userService;

    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        authService = new AuthService(
                userService,
                userRepository,
                passwordHasher,
                tokenProvider,
                sessionService,
                securityEventService,
                refreshTokenRepository,
                tokenRotationService
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail(new Email("user@example.com"));
        testUser.setUsername(new Username("lockoutuser"));
        testUser.setPasswordHash("$2a$10$hashedPassword");
        testUser.setStatus(UserStatus.ACTIVE);
        testUser.setFailedLoginCount(0);
        testUser.setLockedUntil(null);
    }

    @Test
    @DisplayName("Brute-Force: 5 bad passwords increment failed count and lock the account for 15 minutes")
    void shouldLockAccountAfter5FailedAttempts() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches(eq("wrongPassword"), anyString())).thenReturn(false);

        // Attempts 1 to 4: increments count without locking
        for (int i = 1; i <= 4; i++) {
            assertThrows(InvalidCredentialsException.class, () ->
                    authService.login("user@example.com", "wrongPassword")
            );
            assertThat(testUser.getFailedLoginCount()).isEqualTo(i);
            assertThat(testUser.getLockedUntil()).isNull();
        }

        // 5th attempt: should set lockedUntil into the future
        assertThrows(InvalidCredentialsException.class, () ->
                authService.login("user@example.com", "wrongPassword")
        );
        assertThat(testUser.getFailedLoginCount()).isEqualTo(5);
        assertThat(testUser.getLockedUntil()).isNotNull();
        assertThat(testUser.getLockedUntil()).isAfter(Instant.now());

        verify(securityEventService).recordEvent(eq(testUser.getId()), isNull(), eq("ACCOUNT_LOCKED_BRUTE_FORCE"), any(), any(), any());
    }

    @Test
    @DisplayName("Lockout: 6th attempt is rejected immediately with AccountLockedException before checking password")
    void shouldRejectImmediatelyWhenAccountLocked() {
        testUser.setFailedLoginCount(5);
        testUser.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));

        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(testUser));

        AccountLockedException exception = assertThrows(AccountLockedException.class, () ->
                authService.login("user@example.com", "evenRightPassword")
        );

        assertThat(exception.getMessage()).contains("Account is locked");
        // Password hasher should never be called when account is locked
        verify(passwordHasher, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Successful Login: Clears failed_login_count and resets locked_until")
    void shouldResetFailedCountOnSuccessfulLogin() {
        testUser.setFailedLoginCount(3);
        testUser.setLockedUntil(null);

        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(testUser));
        when(passwordHasher.matches("correctPassword", testUser.getPasswordHash())).thenReturn(true);
        when(tokenProvider.generateToken(any())).thenReturn("valid.jwt");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);

        Session session = new Session();
        session.setId(UUID.randomUUID());
        when(sessionService.createSession(any(), any(), any())).thenReturn(session);

        LoginResponse response = authService.login("user@example.com", "correctPassword");

        assertThat(response).isNotNull();
        assertThat(testUser.getFailedLoginCount()).isZero();
        assertThat(testUser.getLockedUntil()).isNull();
        verify(userRepository).save(testUser);
    }
}
