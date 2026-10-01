package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.domain.exception.ResourceNotFoundException;
import com.forgeai.identity.domain.model.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;

    @Transactional
    public Session createSession(UUID userId, Instant expiresAt, String metadata) {
        Session session = new Session();
        session.setUserId(userId);
        session.setCreatedAt(Instant.now());
        session.setLastUsedAt(Instant.now());
        session.setExpiresAt(expiresAt);
        session.setMetadata(metadata);
        
        return sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<Session> getUserSessions(UUID userId) {
        return sessionRepository.findActiveByUserId(userId);
    }

    @Transactional
    public void revokeSession(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));
        
        session.setRevokedAt(Instant.now());
        sessionRepository.save(session);
    }

    @Transactional
    public void revokeAllUserSessions(UUID userId) {
        List<Session> activeSessions = sessionRepository.findActiveByUserId(userId);
        for (Session session : activeSessions) {
            session.setRevokedAt(Instant.now());
            sessionRepository.save(session);
        }
    }
}
