package com.forgeai.identity.presentation.mapper;

import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.presentation.response.ProfileResponse;
import org.springframework.stereotype.Component;

/**
 * Maps Presentation Profile Requests to Application Commands,
 * and Application DTOs to Presentation Responses.
 */
@Component
public class ProfilePresentationMapper {

    public ProfileResponse toResponse(CurrentUserDto dto) {
        // Fallback or derive from User aggregate if properties exist, 
        // currently CurrentUserDto only exposes email and basic state in this domain structure.
        return new ProfileResponse(dto.email(), ""); 
    }
}
