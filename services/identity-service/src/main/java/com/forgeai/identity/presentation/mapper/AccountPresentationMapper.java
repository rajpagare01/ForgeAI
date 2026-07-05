package com.forgeai.identity.presentation.mapper;

import com.forgeai.identity.application.command.ChangePasswordCommand;
import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.presentation.request.ChangePasswordRequest;
import com.forgeai.identity.presentation.response.UserResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Maps Presentation Account Requests to Application Commands,
 * and Application DTOs to Presentation Responses.
 */
@Component
public class AccountPresentationMapper {

    public ChangePasswordCommand toCommand(UUID userId, ChangePasswordRequest request) {
        return new ChangePasswordCommand(userId, request.currentPassword(), request.newPassword());
    }

    public UserResponse toResponse(CurrentUserDto dto) {
        return new UserResponse(
            dto.id(),
            dto.email(),
            dto.status(),
            dto.mfaEnabled(),
            dto.createdAt(),
            dto.createdAt() // CurrentUserDto might not have updatedAt, fallback to createdAt
        );
    }
}
