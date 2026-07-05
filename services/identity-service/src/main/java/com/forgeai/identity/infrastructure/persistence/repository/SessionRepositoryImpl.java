package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.aggregate.SessionStatus;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.valueobject.SessionId;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.SessionJpaEntity;
import com.forgeai.identity.infrastructure.persistence.exception.PersistenceExceptionTranslator;
import com.forgeai.identity.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.forgeai.identity.infrastructure.persistence.mapper.SessionPersistenceMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class SessionRepositoryImpl implements SessionRepository {

    private final SessionJpaRepository jpaRepository;
    private final SessionPersistenceMapper sessionMapper;
    private final RefreshTokenMapper refreshTokenMapper;

    public SessionRepositoryImpl(SessionJpaRepository jpaRepository, SessionPersistenceMapper sessionMapper, RefreshTokenMapper refreshTokenMapper) {
        this.jpaRepository = jpaRepository;
        this.sessionMapper = sessionMapper;
        this.refreshTokenMapper = refreshTokenMapper;
    }

    @Override
    public void save(Session session) {
        try {
            SessionJpaEntity entity = sessionMapper.toEntity(session);
            if (session.getRefreshToken() != null) {
                entity.setRefreshToken(refreshTokenMapper.toEntity(session.getRefreshToken(), entity));
            }
            jpaRepository.save(entity);
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public Optional<Session> findById(SessionId sessionId) {
        try {
            return jpaRepository.findById(sessionId.value())
                    .map(entity -> {
                        var refreshToken = entity.getRefreshToken() != null ? refreshTokenMapper.toDomain(entity.getRefreshToken()) : null;
                        return sessionMapper.toDomain(entity, refreshToken);
                    });
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public List<Session> findActiveSessions(UserId userId) {
        try {
            return jpaRepository.findByUserIdAndStatus(userId.value(), SessionStatus.ACTIVE.name())
                    .stream()
                    .map(entity -> {
                        var refreshToken = entity.getRefreshToken() != null ? refreshTokenMapper.toDomain(entity.getRefreshToken()) : null;
                        return sessionMapper.toDomain(entity, refreshToken);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public void revokeSession(SessionId sessionId) {
        try {
            jpaRepository.findById(sessionId.value()).ifPresent(entity -> {
                entity.setStatus(SessionStatus.REVOKED);
                if (entity.getRefreshToken() != null) {
                    entity.getRefreshToken().setRevoked(true);
                }
                jpaRepository.save(entity);
            });
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public void deleteExpiredSessions() {
        try {
            // Delete sessions that have been inactive for more than 30 days
            Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
            jpaRepository.deleteExpiredSessions(threshold);
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }
}
