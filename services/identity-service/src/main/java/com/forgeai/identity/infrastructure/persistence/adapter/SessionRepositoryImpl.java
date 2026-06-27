package com.forgeai.identity.infrastructure.persistence.adapter;

import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.aggregate.SessionStatus;
import com.forgeai.identity.domain.entity.RefreshToken;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.valueobject.SessionId;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.RefreshTokenJpaEntity;
import com.forgeai.identity.infrastructure.persistence.entity.SessionJpaEntity;
import com.forgeai.identity.infrastructure.persistence.mapper.RefreshTokenMapper;
import com.forgeai.identity.infrastructure.persistence.mapper.SessionPersistenceMapper;
import com.forgeai.identity.infrastructure.persistence.repository.RefreshTokenJpaRepository;
import com.forgeai.identity.infrastructure.persistence.repository.SessionJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class SessionRepositoryImpl implements SessionRepository {

    private final SessionJpaRepository sessionJpaRepository;
    private final RefreshTokenJpaRepository refreshTokenJpaRepository;
    private final SessionPersistenceMapper sessionMapper;
    private final RefreshTokenMapper refreshTokenMapper;

    public SessionRepositoryImpl(
            SessionJpaRepository sessionJpaRepository,
            RefreshTokenJpaRepository refreshTokenJpaRepository,
            SessionPersistenceMapper sessionMapper,
            RefreshTokenMapper refreshTokenMapper) {
        this.sessionJpaRepository = sessionJpaRepository;
        this.refreshTokenJpaRepository = refreshTokenJpaRepository;
        this.sessionMapper = sessionMapper;
        this.refreshTokenMapper = refreshTokenMapper;
    }

    @Override
    public void save(Session session) {
        SessionJpaEntity sessionEntity = sessionMapper.toEntity(session);
        sessionJpaRepository.save(sessionEntity);

        RefreshTokenJpaEntity tokenEntity = refreshTokenMapper.toEntity(session.getRefreshToken(), session.getSessionId().value());
        refreshTokenJpaRepository.save(tokenEntity);
    }

    @Override
    public Optional<Session> findById(SessionId sessionId) {
        return sessionJpaRepository.findById(sessionId.value())
                .flatMap(sessionEntity -> refreshTokenJpaRepository.findBySessionId(sessionId.value())
                        .map(tokenEntity -> {
                            RefreshToken refreshToken = refreshTokenMapper.toDomain(tokenEntity);
                            return sessionMapper.toDomain(sessionEntity, refreshToken);
                        })
                );
    }

    @Override
    public List<Session> findActiveSessions(UserId userId) {
        return sessionJpaRepository.findByUserIdAndStatus(userId.value(), SessionStatus.ACTIVE.name())
                .stream()
                .map(sessionEntity -> {
                    RefreshTokenJpaEntity tokenEntity = refreshTokenJpaRepository.findBySessionId(sessionEntity.getId())
                            .orElseThrow(() -> new IllegalStateException("Session without refresh token"));
                    RefreshToken refreshToken = refreshTokenMapper.toDomain(tokenEntity);
                    return sessionMapper.toDomain(sessionEntity, refreshToken);
                })
                .toList();
    }

    @Override
    public void revokeSession(SessionId sessionId) {
        findById(sessionId).ifPresent(session -> {
            session.revoke();
            save(session);
        });
    }

    @Override
    public void deleteExpiredSessions() {
        // Delete sessions inactive for more than 30 days
        Instant threshold = Instant.now().minus(30, ChronoUnit.DAYS);
        sessionJpaRepository.deleteExpiredSessions(threshold);
    }
}
