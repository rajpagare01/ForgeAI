package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.domain.valueobject.RawPassword;
import com.forgeai.identity.application.port.out.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final SecurityEventService securityEventService;
    private final PasswordHasher passwordHasher;

    @Transactional
    public User createUser(String email, String username, String rawPassword, String firstName, String lastName) {
        Email emailObj = new Email(email);
        Username usernameObj = new Username(username);
        RawPassword passwordObj = new RawPassword(rawPassword);

        if (userRepository.findByEmail(emailObj).isPresent()) {
            throw new UserAlreadyExistsException("Email already in use");
        }
        if (userRepository.findByUsername(usernameObj).isPresent()) {
            throw new UserAlreadyExistsException("Username already in use");
        }

        String hashedPassword = passwordHasher.hash(passwordObj.value());

        User user = new User();
        user.setEmail(emailObj);
        user.setUsername(usernameObj);
        user.setPasswordHash(hashedPassword);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        User savedUser = userRepository.save(user);
        
        securityEventService.recordEvent(savedUser.getId(), null, "USER_REGISTERED", null, null, "{}");

        return savedUser;
    }

    @Transactional(readOnly = true)
    public User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public User getUserByEmail(String email) {
        return userRepository.findByEmail(new Email(email))
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + email));
    }

    @Transactional(readOnly = true)
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(new Username(username))
                .orElseThrow(() -> new UserNotFoundException("User not found with username: " + username));
    }

    @Transactional
    public User updateUser(UUID id, String firstName, String lastName) {
        User user = getUser(id);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User changeUsername(UUID id, String newUsername) {
        User user = getUser(id);
        Username usernameObj = new Username(newUsername);

        if (userRepository.findByUsername(usernameObj).isPresent()) {
            throw new UserAlreadyExistsException("Username already in use");
        }

        user.setUsername(usernameObj);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User requestEmailChange(UUID id, String newEmail) {
        User user = getUser(id);
        Email emailObj = new Email(newEmail);

        if (userRepository.findByEmail(emailObj).isPresent()) {
            throw new UserAlreadyExistsException("Email already in use");
        }

        user.setPendingEmail(emailObj);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        
        securityEventService.recordEvent(savedUser.getId(), null, "EMAIL_CHANGE_REQUESTED", null, null, "{\"newEmail\": \"" + newEmail + "\"}");

        return savedUser;
    }

    @Transactional
    public User suspendUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.SUSPENDED);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        
        sessionService.revokeAllUserSessions(id);
        securityEventService.recordEvent(savedUser.getId(), null, "USER_SUSPENDED", null, null, "{}");
        
        return savedUser;
    }

    @Transactional
    public User lockUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.LOCKED);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        
        sessionService.revokeAllUserSessions(id);
        securityEventService.recordEvent(savedUser.getId(), null, "USER_LOCKED", null, null, "{}");
        
        return savedUser;
    }

    @Transactional
    public User unlockUser(UUID id) {
        User user = getUser(id);
        if (user.getStatus() != UserStatus.LOCKED && user.getStatus() != UserStatus.SUSPENDED) {
            throw new InvalidUserStateException("User is not locked or suspended");
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        
        securityEventService.recordEvent(savedUser.getId(), null, "USER_UNLOCKED", null, null, "{}");
        
        return savedUser;
    }

    @Transactional
    public User deactivateUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.INACTIVE);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        
        sessionService.revokeAllUserSessions(id);
        securityEventService.recordEvent(savedUser.getId(), null, "USER_DEACTIVATED", null, null, "{}");
        
        return savedUser;
    }
}
