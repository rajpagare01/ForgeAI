package com.forgeai.identity.domain.repository;

import java.util.List;
import java.util.Optional;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.valueobject.SessionId;
import com.forgeai.identity.domain.valueobject.UserId;

public interface SessionRepository {
    void save(Session session);
    Optional<Session> findById(SessionId sessionId);
    List<Session> findActiveSessions(UserId userId);
    void revokeSession(SessionId sessionId);
    void deleteExpiredSessions();
}
