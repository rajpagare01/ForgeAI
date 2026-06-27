package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.LogoutCommand;
import com.forgeai.identity.application.exception.SessionOperationException;
import com.forgeai.identity.application.port.CachePort;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.valueobject.SessionId;

import java.time.Duration;

/**
 * Revokes a session upon user logout and blacklists it in the cache
 * so the API Gateway rejects any further JWTs for that session.
 */
public final class LogoutUseCase {

    private final SessionRepository sessionRepository;
    private final EventPublisherPort eventPublisherPort;
    private final CachePort cachePort;

    public LogoutUseCase(
            SessionRepository sessionRepository,
            EventPublisherPort eventPublisherPort,
            CachePort cachePort) {
        this.sessionRepository = sessionRepository;
        this.eventPublisherPort = eventPublisherPort;
        this.cachePort = cachePort;
    }

    public void execute(LogoutCommand command) {
        Session session = sessionRepository.findById(SessionId.of(command.sessionId()))
                .orElseThrow(() -> new SessionOperationException("Session not found."));

        session.revoke();
        sessionRepository.save(session);

        cachePort.put("session:blacklist:" + command.sessionId(), "revoked", Duration.ofMinutes(20));

        eventPublisherPort.publishAll(session.getDomainEvents());
        session.clearDomainEvents();
    }
}
