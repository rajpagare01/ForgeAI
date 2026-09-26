package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public User createUser(String email, String username, String passwordHash, String firstName, String lastName) {
        Email emailObj = new Email(email);
        Username usernameObj = new Username(username);

        if (userRepository.findByEmail(emailObj).isPresent()) {
            throw new UserAlreadyExistsException("Email already in use");
        }
        if (userRepository.findByUsername(usernameObj).isPresent()) {
            throw new UserAlreadyExistsException("Username already in use");
        }

        User user = new User();
        user.setEmail(emailObj);
        user.setUsername(usernameObj);
        user.setPasswordHash(passwordHash);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        return userRepository.save(user);
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
        return userRepository.save(user);
    }

    @Transactional
    public User suspendUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.SUSPENDED);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User lockUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.LOCKED);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User unlockUser(UUID id) {
        User user = getUser(id);
        if (user.getStatus() != UserStatus.LOCKED && user.getStatus() != UserStatus.SUSPENDED) {
            throw new InvalidUserStateException("User is not locked or suspended");
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    @Transactional
    public User deactivateUser(UUID id) {
        User user = getUser(id);
        user.setStatus(UserStatus.DEACTIVATED);
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }
}
