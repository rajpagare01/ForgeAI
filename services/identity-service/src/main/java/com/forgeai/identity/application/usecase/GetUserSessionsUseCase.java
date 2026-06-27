package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.dto.SessionDto;
import com.forgeai.identity.application.mapper.SessionMapper;
import com.forgeai.identity.application.query.GetUserSessionsQuery;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.valueobject.UserId;

import java.util.List;

/**
 * Retrieves all active sessions for a specific user.
 */
public final class GetUserSessionsUseCase {

    private final SessionRepository sessionRepository;

    public GetUserSessionsUseCase(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public List<SessionDto> execute(GetUserSessionsQuery query) {
        List<Session> sessions = sessionRepository.findActiveSessions(UserId.of(query.userId()));
        return SessionMapper.toDtoList(sessions);
    }
}
