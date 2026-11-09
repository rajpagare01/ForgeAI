package com.forgeai.identity.application.service;

import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.domain.exception.ResourceNotFoundException;
import com.forgeai.identity.domain.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class SessionService {

    public static final String REVOCATION_KEY_PREFIX = "blacklist:session:";

    private final SessionRepository sessionRepository;
    private final StringRedisTemplate redisTemplate;

    public SessionService(SessionRepository sessionRepository) {
        this(sessionRepository, null);
    }

    @Autowired
    public SessionService(SessionRepository sessionRepository,
                          @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
    }

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
        blacklistSessionInRedis(sessionId);
    }

    @Transactional
    public void revokeAllUserSessions(UUID userId) {
        List<Session> activeSessions = sessionRepository.findActiveByUserId(userId);
        for (Session session : activeSessions) {
            session.setRevokedAt(Instant.now());
            sessionRepository.save(session);
            blacklistSessionInRedis(session.getId());
        }
    }

    private void blacklistSessionInRedis(UUID sessionId) {
        if (sessionId == null || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REVOCATION_KEY_PREFIX + sessionId, "revoked", Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("Failed to write session {} revocation to Redis blacklist: {}", sessionId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Session getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    @Transactional
    public void updateSession(Session session) {
        sessionRepository.save(session);
    }
}
