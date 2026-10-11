package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.domain.model.SecurityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SecurityEventRepository securityEventRepository;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TokenRotationService tokenRotationService;

    private AuthService authService;

    private User activeUser;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "Test-Agent");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SessionService sessionService = new SessionService(sessionRepository);
        SecurityEventService securityEventService = new SecurityEventService(securityEventRepository);
        com.forgeai.identity.infrastructure.outbox.OutboxService outboxService = mock(com.forgeai.identity.infrastructure.outbox.OutboxService.class);
        var emailTokenRepo = mock(com.forgeai.identity.infrastructure.adapter.out.persistence.repository.EmailVerificationTokenRepository.class);
        UserService userService = new UserService(userRepository, sessionService, securityEventService, passwordHasher, refreshTokenRepository, outboxService, emailTokenRepo);

        authService = new AuthService(userService, userRepository, passwordHasher, tokenProvider, sessionService, securityEventService, refreshTokenRepository, tokenRotationService);

        activeUser = new User();
        activeUser.setId(UUID.randomUUID());
        activeUser.setEmail(new Email("test@example.com"));
        activeUser.setUsername(new Username("testuser"));
        activeUser.setStatus(UserStatus.ACTIVE);
        activeUser.setPasswordHash("hashed_password");
    }

    @Test
    void register_Success() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(passwordHasher.hash(anyString())).thenReturn("hashed_password");
        when(userRepository.save(any())).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        User created = authService.register("newuser@example.com", "newuser", "StrongP@ss1", "Test", "User");

        assertNotNull(created);
        assertEquals("newuser@example.com", created.getEmail().value());
        assertNotEquals("StrongP@ss1", created.getPasswordHash());
        assertEquals("hashed_password", created.getPasswordHash());
        verify(userRepository).save(any(User.class));
        verify(securityEventRepository).save(any());
    }

    @Test
    void register_DuplicateEmail() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));
        assertThrows(UserAlreadyExistsException.class, () -> authService.register("test@example.com", "newuser", "StrongP@ss1", "Test", "User"));
    }

    @Test
    void register_DuplicateUsername() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.of(activeUser));
        assertThrows(UserAlreadyExistsException.class, () -> authService.register("newuser@example.com", "testuser", "StrongP@ss1", "Test", "User"));
    }

    @Test
    void register_WeakPassword() {
        assertThrows(IllegalArgumentException.class, () -> authService.register("new@example.com", "newuser", "weak", "Test", "User"));
    }

    @Test
    void register_InvalidEmail() {
        assertThrows(IllegalArgumentException.class, () -> authService.register("invalid-email", "newuser", "StrongP@ss1", "Test", "User"));
    }

    @Test
    void login_Success_WithEmail() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("token123");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);
        Session session = new Session();
        session.setId(UUID.randomUUID());
        when(sessionRepository.save(any(Session.class))).thenReturn(session);

        LoginResponse response = authService.login("test@example.com", "StrongP@ss1");

        assertNotNull(response);
        assertEquals("token123", response.accessToken());
        assertEquals(activeUser.getId(), response.user().id());
        assertNotNull(response.refreshToken());
        verify(userRepository).findByEmail(new Email("test@example.com"));
        verify(refreshTokenRepository).save(any());
        verify(securityEventRepository).save(any());
    }

    @Test
    void login_Success_WithUsername() {
        when(userRepository.findByUsername(any(Username.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("token123");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);
        Session session = new Session();
        session.setId(UUID.randomUUID());
        when(sessionRepository.save(any(Session.class))).thenReturn(session);

        LoginResponse response = authService.login("testuser", "StrongP@ss1");

        assertNotNull(response);
        assertEquals("token123", response.accessToken());
        assertEquals(activeUser.getId(), response.user().id());
        assertNotNull(response.refreshToken());
        verify(userRepository).findByUsername(new Username("testuser"));
        verify(refreshTokenRepository).save(any());
        verify(securityEventRepository).save(any());
    }

    @Test
    void login_InvalidPassword() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("WrongPass1!", "hashed_password")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.login("test@example.com", "WrongPass1!"));
        verify(securityEventRepository).save(any()); // LOGIN_FAILURE
        verify(sessionRepository, never()).save(any());
        verify(refreshTokenRepository, never()).save(any());
        verify(tokenProvider, never()).generateToken(any());
    }

    @Test
    void login_UnknownIdentifier() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.login("unknown@example.com", "StrongP@ss1"));
        verify(securityEventRepository).save(any()); // LOGIN_FAILURE
    }

    @Test
    void login_SuspendedUser() {
        activeUser.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);

        assertThrows(InvalidUserStateException.class, () -> authService.login("test@example.com", "StrongP@ss1"));
        verify(securityEventRepository).save(any()); // LOGIN_BLOCKED
    }

    @Test
    void login_LockedUser() {
        activeUser.setStatus(UserStatus.LOCKED);
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);

        assertThrows(InvalidUserStateException.class, () -> authService.login("test@example.com", "StrongP@ss1"));
        verify(securityEventRepository).save(any()); // LOGIN_BLOCKED
    }

    @Test
    void login_DeactivatedUser() {
        activeUser.setStatus(UserStatus.DEACTIVATED);
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);

        assertThrows(InvalidUserStateException.class, () -> authService.login("test@example.com", "StrongP@ss1"));
        verify(securityEventRepository).save(any()); // LOGIN_BLOCKED
    }

    @Test
    void refresh_DelegatesToTokenRotationService() {
        var expectedResponse = new com.forgeai.identity.api.dto.RefreshTokenResponse(
                "new_access_token",
                "new_refresh_token",
                "Bearer",
                900L
        );
        when(tokenRotationService.rotateToken("some_raw_token")).thenReturn(expectedResponse);

        var response = authService.refresh("some_raw_token");

        assertNotNull(response);
        assertEquals("new_access_token", response.accessToken());
        assertEquals("new_refresh_token", response.refreshToken());
        verify(tokenRotationService).rotateToken("some_raw_token");
    }

    @Test
    void logout_Success() {
        Session session = new Session();
        session.setId(UUID.randomUUID());
        session.setUserId(activeUser.getId());

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setId(UUID.randomUUID());
        refreshToken.setSessionId(session.getId());

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(refreshToken));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        authService.logout(activeUser.getId(), "some_raw_token");

        verify(sessionRepository).save(argThat(s -> s.getRevokedAt() != null));
        verify(refreshTokenRepository).revokeBySessionId(session.getId());
        verify(securityEventRepository).save(argThat((SecurityEvent e) -> e.getEventType().equals("LOGOUT")));
    }

    @Test
    void logoutAll_Success() {
        Session session1 = new Session();
        session1.setId(UUID.randomUUID());
        
        when(sessionRepository.findActiveByUserId(activeUser.getId())).thenReturn(java.util.List.of(session1));

        authService.logoutAll(activeUser.getId());

        verify(sessionRepository).save(argThat(s -> s.getRevokedAt() != null));
        verify(refreshTokenRepository).revokeBySessionId(session1.getId());
        verify(securityEventRepository).save(argThat((SecurityEvent e) -> e.getEventType().equals("LOGOUT_ALL_SESSIONS")));
    }
}
