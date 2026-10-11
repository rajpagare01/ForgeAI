package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.*;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.domain.valueobject.RawPassword;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.RefreshTokenRepository;
import com.forgeai.identity.domain.event.UserCreatedEvent;
import com.forgeai.identity.domain.event.UserUpdatedEvent;
import com.forgeai.identity.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final SecurityEventService securityEventService;
    private final PasswordHasher passwordHasher;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OutboxService outboxService;
    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.EmailVerificationTokenRepository emailVerificationTokenRepository;

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
        
        String ipAddress = null;
        String userAgent = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            ipAddress = req.getRemoteAddr();
            String forwardedFor = req.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                ipAddress = forwardedFor.split(",")[0].trim();
            }
            userAgent = req.getHeader("User-Agent");
        }

        securityEventService.recordEvent(savedUser.getId(), null, "USER_REGISTERED", ipAddress, userAgent, "{}");

        if (outboxService != null) {
            outboxService.publish(UserCreatedEvent.builder()
                    .userId(savedUser.getId())
                    .email(savedUser.getEmail().value())
                    .firstName(savedUser.getFirstName())
                    .lastName(savedUser.getLastName())
                    .build(), null);
        }

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
        if (firstName != null) user.setFirstName(firstName.strip());
        if (lastName != null) user.setLastName(lastName.strip());
        user.setUpdatedAt(Instant.now());
        User saved = userRepository.save(user);
        securityEventService.recordEvent(saved.getId(), null, "PROFILE_UPDATED", null, null, "{}");

        if (outboxService != null) {
            outboxService.publish(UserUpdatedEvent.builder()
                    .userId(saved.getId())
                    .firstName(saved.getFirstName())
                    .lastName(saved.getLastName())
                    .status(saved.getStatus() != null ? saved.getStatus().name() : null)
                    .build(), null);
        }

        return saved;
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
        User saved = userRepository.save(user);
        securityEventService.recordEvent(saved.getId(), null, "USERNAME_CHANGED", null, null, "{}");
        return saved;
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

        // Generate cryptographically secure 64-character verification token
        String rawToken = generateSecureToken(64);
        String tokenHash = hashSha256(rawToken);

        if (emailVerificationTokenRepository != null) {
            com.forgeai.identity.infrastructure.adapter.out.persistence.entity.EmailVerificationTokenJpaEntity tokenEntity =
                    new com.forgeai.identity.infrastructure.adapter.out.persistence.entity.EmailVerificationTokenJpaEntity();
            tokenEntity.setUserId(savedUser.getId());
            tokenEntity.setNewEmail(newEmail);
            tokenEntity.setTokenHash(tokenHash);
            tokenEntity.setExpiresAt(Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS));
            tokenEntity.setCreatedAt(Instant.now());
            emailVerificationTokenRepository.save(tokenEntity);
        }

        if (outboxService != null) {
            outboxService.publish(com.forgeai.identity.domain.event.EmailVerificationRequestedEvent.builder()
                    .userId(savedUser.getId())
                    .newEmail(newEmail)
                    .verificationToken(rawToken)
                    .build(), null);
        }
        
        securityEventService.recordEvent(savedUser.getId(), null, "EMAIL_CHANGE_REQUESTED", null, null, "{\"newEmail\": \"" + newEmail + "\"}");

        return savedUser;
    }

    @Transactional
    public User verifyEmailChange(UUID id, String token) {
        User user = getUser(id);
        String tokenHash = hashSha256(token);

        com.forgeai.identity.infrastructure.adapter.out.persistence.entity.EmailVerificationTokenJpaEntity tokenEntity =
                emailVerificationTokenRepository.findByTokenHash(tokenHash)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid verification token"));

        if (!tokenEntity.getUserId().equals(id)) {
            throw new IllegalArgumentException("Token does not belong to the current user");
        }

        if (tokenEntity.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Verification token has expired");
        }

        if (tokenEntity.getUsedAt() != null) {
            throw new IllegalArgumentException("Verification token has already been used");
        }

        Email newEmailObj = new Email(tokenEntity.getNewEmail());
        if (userRepository.findByEmail(newEmailObj).isPresent()) {
            throw new UserAlreadyExistsException("Email already in use");
        }

        user.setEmail(newEmailObj);
        user.setPendingEmail(null);
        user.setEmailVerified(true);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        tokenEntity.setUsedAt(Instant.now());
        emailVerificationTokenRepository.save(tokenEntity);

        if (outboxService != null) {
            outboxService.publish(com.forgeai.identity.domain.event.UserUpdatedEvent.builder()
                    .userId(savedUser.getId())
                    .firstName(savedUser.getFirstName())
                    .lastName(savedUser.getLastName())
                    .status(savedUser.getStatus() != null ? savedUser.getStatus().name() : null)
                    .build(), null);
        }

        securityEventService.recordEvent(savedUser.getId(), null, "EMAIL_VERIFIED_AND_CHANGED", null, null,
                "{\"newEmail\": \"" + savedUser.getEmail().value() + "\"}");

        return savedUser;
    }

    private String generateSecureToken(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String hashSha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error", e);
        }
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
        user.setStatus(UserStatus.DEACTIVATED);
        user.setUpdatedAt(Instant.now());
        User savedUser = userRepository.save(user);

        // Revoke all sessions and their associated refresh tokens
        sessionService.getUserSessions(id).forEach(s -> refreshTokenRepository.revokeBySessionId(s.getId()));
        sessionService.revokeAllUserSessions(id);
        securityEventService.recordEvent(savedUser.getId(), null, "ACCOUNT_DEACTIVATED", null, null, "{}");

        return savedUser;
    }

    /**
     * Change password after verifying the current password.
     * Revokes all sessions and refresh tokens to force re-authentication.
     */
    @Transactional
    public void changePassword(UUID id, String currentPassword, String newPassword) {
        User user = getUser(id);

        if (!passwordHasher.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        RawPassword newPwd = new RawPassword(newPassword); // validates policy
        user.setPasswordHash(passwordHasher.hash(newPwd.value()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        // Revoke all sessions and refresh tokens — force re-authentication
        sessionService.getUserSessions(id).forEach(s -> refreshTokenRepository.revokeBySessionId(s.getId()));
        sessionService.revokeAllUserSessions(id);

        securityEventService.recordEvent(id, null, "PASSWORD_CHANGED", null, null, "{}");
        securityEventService.recordEvent(id, null, "ALL_SESSIONS_REVOKED_AFTER_PASSWORD_CHANGE", null, null, "{}");
    }
}
