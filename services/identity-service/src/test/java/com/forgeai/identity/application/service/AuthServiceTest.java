package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private AuthService authService;

    private User activeUser;

    @BeforeEach
    void setUp() {
        // Build a real UserService from mocked ports — avoids mocking concrete Spring beans
        SessionService sessionService = new SessionService(sessionRepository);
        SecurityEventService securityEventService = new SecurityEventService(securityEventRepository);
        UserService userService = new UserService(userRepository, sessionService, securityEventService, passwordHasher);

        authService = new AuthService(userService, userRepository, passwordHasher, tokenProvider);

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
        when(userRepository.save(any())).thenReturn(activeUser);

        User created = authService.register("test@example.com", "testuser", "StrongP@ss1", "Test", "User");

        assertNotNull(created);
        assertEquals("test@example.com", created.getEmail().value());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void login_Success_WithEmail() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("token123");

        LoginResponse response = authService.login("test@example.com", "StrongP@ss1");

        assertNotNull(response);
        assertEquals("token123", response.accessToken());
        assertEquals(activeUser.getId(), response.userId());
        verify(userRepository).findByEmail(new Email("test@example.com"));
    }

    @Test
    void login_Success_WithUsername() {
        when(userRepository.findByUsername(any(Username.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);
        when(tokenProvider.generateToken(activeUser.getId())).thenReturn("token123");

        LoginResponse response = authService.login("testuser", "StrongP@ss1");

        assertNotNull(response);
        assertEquals("token123", response.accessToken());
        verify(userRepository).findByUsername(new Username("testuser"));
    }

    @Test
    void login_InvalidPassword() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("WrongPass1!", "hashed_password")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.login("test@example.com", "WrongPass1!"));
    }

    @Test
    void login_UnknownIdentifier() {
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.login("unknown@example.com", "StrongP@ss1"));
    }

    @Test
    void login_InactiveUser() {
        activeUser.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findByEmail(any(Email.class))).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("StrongP@ss1", "hashed_password")).thenReturn(true);

        assertThrows(InvalidUserStateException.class, () -> authService.login("test@example.com", "StrongP@ss1"));
    }
}

