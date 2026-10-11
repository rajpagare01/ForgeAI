package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.UserProfileResponse;
import com.forgeai.identity.application.port.in.UserProfileUseCase;
import com.forgeai.identity.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Adapter that implements {@link UserProfileUseCase} by delegating to
 * {@link UserService}. Keeps the controller layer thin and maps domain
 * objects to public response DTOs — never exposing the domain model directly.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService implements UserProfileUseCase {

    private final UserService userService;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userService.getUser(userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, String firstName, String lastName) {
        User user = userService.updateUser(userId, firstName, lastName);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse changeUsername(UUID userId, String newUsername) {
        User user = userService.changeUsername(userId, newUsername);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse requestEmailChange(UUID userId, String newEmail) {
        User user = userService.requestEmailChange(userId, newEmail);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse verifyEmailChange(UUID userId, String token) {
        User user = userService.verifyEmailChange(userId, token);
        return toResponse(user);
    }


    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        userService.changePassword(userId, currentPassword, newPassword);
    }

    @Override
    @Transactional
    public void deactivateAccount(UUID userId) {
        userService.deactivateUser(userId);
    }

    // -------------------------------------------------------------------------
    // Mapping — never exposes passwordHash, refresh tokens, session internals
    // -------------------------------------------------------------------------
    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail().value(),
                user.getUsername().value(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name(),
                user.isEmailVerified(),
                user.getPendingEmail() != null ? user.getPendingEmail().value() : null,
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
