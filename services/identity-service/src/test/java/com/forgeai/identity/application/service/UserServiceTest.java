package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    
    @Mock
    private SessionRepository sessionRepository;

    private SessionService sessionService;
    
    @Mock
    private SecurityEventRepository securityEventRepository;

    private SecurityEventService securityEventService;

    @Mock
    private com.forgeai.identity.application.port.out.PasswordHasher passwordHasher;

    @InjectMocks
    private UserService userService;

    private User activeUser;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(sessionRepository);
        securityEventService = new SecurityEventService(securityEventRepository);
        userService = new UserService(userRepository, sessionService, securityEventService, passwordHasher);
        
        activeUser = new User();
        activeUser.setId(UUID.randomUUID());
        activeUser.setEmail(new Email("test@example.com"));
        activeUser.setUsername(new Username("testuser"));
        activeUser.setStatus(UserStatus.ACTIVE);
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
    }

    @Test
    void requestEmailChange_Success() {
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.findByEmail(new Email("new@example.com"))).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.requestEmailChange(activeUser.getId(), "new@example.com");
        
        assertEquals("new@example.com", activeUser.getPendingEmail().value());
        verify(userRepository).save(activeUser);
        verify(securityEventRepository).save(any());
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
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        when(userRepository.save(any())).thenReturn(activeUser);

        userService.deactivateUser(activeUser.getId());
        
        assertEquals(UserStatus.INACTIVE, activeUser.getStatus());
        verify(sessionRepository).findActiveByUserId(activeUser.getId());
        verify(securityEventRepository).save(any());
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
