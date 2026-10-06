package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SecurityEventRepository securityEventRepository;

    @Mock
    private com.forgeai.identity.application.port.out.PasswordHasher passwordHasher;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private UserService userService;

    private User activeUser;

    @BeforeEach
    void setUp() {
        SessionService sessionService = new SessionService(sessionRepository);
        SecurityEventService securityEventService = new SecurityEventService(securityEventRepository);
        userService = new UserService(userRepository, sessionService, securityEventService, passwordHasher, refreshTokenRepository);

        activeUser = new User();
        activeUser.setId(UUID.randomUUID());
        activeUser.setEmail(new Email("test@example.com"));
        activeUser.setUsername(new Username("testuser"));
        activeUser.setStatus(UserStatus.ACTIVE);
        activeUser.setPasswordHash("hashed_password");
        activeUser.setCreatedAt(Instant.now());
        activeUser.setUpdatedAt(Instant.now());
    }

    @Test
    void createUser_Success() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(passwordHasher.hash(anyString())).thenReturn("hashed_password");
        when(userRepository.save(any())).thenReturn(activeUser);

        User created = userService.createUser("test@example.com", "testuser", "StrongP@ss1", "Test", "User");

        assertNotNull(created);
        verify(userRepository).save(any(User.class));
        verify(securityEventRepository).save(any());
    }

    @Test
    void createUser_DuplicateEmail() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));

        assertThrows(UserAlreadyExistsException.class, () ->
            userService.createUser("test@example.com", "testuser", "StrongP@ss1", "Test", "User")
        );
    }

    @Test
    void createUser_DuplicateUsername() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.of(activeUser));

        assertThrows(UserAlreadyExistsException.class, () ->
            userService.createUser("test@example.com", "testuser", "StrongP@ss1", "Test", "User")
        );
    }

    @Test
    void changeUsername_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.findByUsername(new Username("newuser"))).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.changeUsername(activeUser.getId(), "newuser");

        assertEquals("newuser", activeUser.getUsername().value());
        verify(userRepository).save(activeUser);
        verify(securityEventRepository).save(any()); // USERNAME_CHANGED
    }

    @Test
    void changeUsername_DuplicateUsername_Throws() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.findByUsername(any())).thenReturn(Optional.of(activeUser));

        assertThrows(UserAlreadyExistsException.class, () ->
                userService.changeUsername(activeUser.getId(), "taken"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void requestEmailChange_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.findByEmail(new Email("new@example.com"))).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.requestEmailChange(activeUser.getId(), "new@example.com");

        assertEquals("new@example.com", activeUser.getPendingEmail().value());
        verify(userRepository).save(activeUser);
        verify(securityEventRepository).save(any()); // EMAIL_CHANGE_REQUESTED
    }

    @Test
    void requestEmailChange_DuplicateEmail_Throws() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(activeUser));

        assertThrows(UserAlreadyExistsException.class, () ->
                userService.requestEmailChange(activeUser.getId(), "taken@example.com"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateUser_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.updateUser(activeUser.getId(), "NewFirst", "NewLast");

        assertEquals("NewFirst", activeUser.getFirstName());
        assertEquals("NewLast", activeUser.getLastName());
        verify(securityEventRepository).save(any()); // PROFILE_UPDATED
    }

    @Test
    void changePassword_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("CurrentP@ss1", "hashed_password")).thenReturn(true);
        when(passwordHasher.hash(anyString())).thenReturn("new_hashed");
        when(sessionRepository.findActiveByUserId(activeUser.getId())).thenReturn(List.of());
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.changePassword(activeUser.getId(), "CurrentP@ss1", "NewStr0ng!Pass");

        verify(userRepository).save(any());
        verify(securityEventRepository, times(2)).save(any()); // PASSWORD_CHANGED + ALL_SESSIONS_REVOKED
    }

    @Test
    void changePassword_WrongCurrentPassword_Throws() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("wrong", "hashed_password")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () ->
                userService.changePassword(activeUser.getId(), "wrong", "NewStr0ng!Pass"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_WeakNewPassword_Throws() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(passwordHasher.matches("CurrentP@ss1", "hashed_password")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                userService.changePassword(activeUser.getId(), "CurrentP@ss1", "weak"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void suspendUser_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.suspendUser(activeUser.getId());

        assertEquals(UserStatus.SUSPENDED, activeUser.getStatus());
        verify(sessionRepository).findActiveByUserId(activeUser.getId());
        verify(securityEventRepository).save(any());
    }

    @Test
    void lockUser_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.lockUser(activeUser.getId());

        assertEquals(UserStatus.LOCKED, activeUser.getStatus());
        verify(sessionRepository).findActiveByUserId(activeUser.getId());
        verify(securityEventRepository).save(any());
    }

    @Test
    void deactivateUser_Success() {
        Session s = new Session();
        s.setId(UUID.randomUUID());

        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);
        when(sessionRepository.findActiveByUserId(activeUser.getId())).thenReturn(List.of(s));

        userService.deactivateUser(activeUser.getId());

        assertEquals(UserStatus.DEACTIVATED, activeUser.getStatus());
        verify(refreshTokenRepository).revokeBySessionId(s.getId());
        verify(sessionRepository, times(2)).findActiveByUserId(activeUser.getId());
        verify(securityEventRepository).save(any()); // ACCOUNT_DEACTIVATED
    }

    @Test
    void unlockUser_Success() {
        activeUser.setStatus(UserStatus.LOCKED);
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.unlockUser(activeUser.getId());

        assertEquals(UserStatus.ACTIVE, activeUser.getStatus());
        verify(securityEventRepository).save(any());
    }
}
