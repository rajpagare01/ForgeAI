package com.forgeai.identity.application.mapper;

import com.forgeai.identity.application.dto.SessionDto;
import com.forgeai.identity.domain.aggregate.Session;

import java.util.List;

/**
 * Maps the Session aggregate to application-layer DTOs.
 */
public final class SessionMapper {

    private SessionMapper() {
    }

    /**
     * Converts a Session aggregate into a SessionDto.
     *
     * @param session the domain aggregate
     * @return an immutable DTO projection
     */
    public static SessionDto toDto(Session session) {
        return new SessionDto(
                session.getSessionId().value(),
                session.getUserId().value(),
                session.getDeviceInfo().value(),
                session.getIpAddress().value(),
                session.getUserAgent().value(),
                session.getStatus().name(),
                session.getCreatedAt(),
                session.getLastAccessed());
    }

    /**
     * Converts a list of Session aggregates into a list of DTOs.
     *
     * @param sessions the domain aggregates
     * @return a list of immutable DTO projections
     */
    public static List<SessionDto> toDtoList(List<Session> sessions) {
        return sessions.stream()
                .map(SessionMapper::toDto)
                .toList();
    }
}
