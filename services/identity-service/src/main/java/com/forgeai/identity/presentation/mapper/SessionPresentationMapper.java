package com.forgeai.identity.presentation.mapper;

import com.forgeai.identity.application.dto.SessionDto;
import com.forgeai.identity.presentation.response.SessionResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps Application Session DTOs to Presentation Responses.
 */
@Component
public class SessionPresentationMapper {

    public SessionResponse toResponse(SessionDto dto) {
        return new SessionResponse(
            dto.id(),
            dto.deviceInfo(),
            dto.ipAddress(),
            dto.userAgent(),
            dto.createdAt(),
            dto.lastAccessed()
        );
    }

    public List<SessionResponse> toResponseList(List<SessionDto> dtos) {
        return dtos.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
