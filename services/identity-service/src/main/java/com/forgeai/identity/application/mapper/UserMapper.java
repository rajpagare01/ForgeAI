package com.forgeai.identity.application.mapper;

import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.application.dto.UserDto;
import com.forgeai.identity.domain.aggregate.User;

/**
 * Maps the User aggregate to application-layer DTOs.
 * Prevents domain objects from leaking beyond the application boundary.
 */
public final class UserMapper {

    private UserMapper() {
        // Utility-free: instantiate where needed
    }

    /**
     * Converts a User aggregate into a UserDto.
     *
     * @param user the domain aggregate
     * @return an immutable DTO projection
     */
    public static UserDto toDto(User user) {
        return new UserDto(
                user.getUserId().value(),
                user.getEmail().value(),
                user.getStatus().name(),
                user.isMfaEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    /**
     * Converts a User aggregate into a CurrentUserDto (for /users/me).
     *
     * @param user the domain aggregate
     * @return an immutable DTO projection
     */
    public static CurrentUserDto toCurrentUserDto(User user) {
        return new CurrentUserDto(
                user.getUserId().value(),
                user.getEmail().value(),
                user.getStatus().name(),
                user.isMfaEnabled(),
                user.getCreatedAt());
    }
}
