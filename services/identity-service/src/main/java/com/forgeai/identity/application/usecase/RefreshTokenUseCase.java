package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.RefreshTokenCommand;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.application.exception.SessionOperationException;
import com.forgeai.identity.application.port.ClockPort;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.port.JwtGeneratorPort;
import com.forgeai.identity.application.port.PasswordHasherPort;
import com.forgeai.identity.application.port.UuidGeneratorPort;
import com.forgeai.identity.application.validator.RefreshTokenValidator;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.valueobject.SessionId;

import java.time.Duration;
import java.util.Map;

/**
 * Orchestrates refresh-token rotation:
 * validates the current token, rotates it inside the Session aggregate,
 * issues a new JWT access token, and publishes resulting domain events.
 */
public final class RefreshTokenUseCase {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private final SessionRepository sessionRepository;
    private final PasswordHasherPort passwordHasherPort;
    private final JwtGeneratorPort jwtGeneratorPort;
    private final UuidGeneratorPort uuidGeneratorPort;
    private final ClockPort clockPort;
    private final EventPublisherPort eventPublisherPort;

    public RefreshTokenUseCase(
            SessionRepository sessionRepository,
            PasswordHasherPort passwordHasherPort,
            JwtGeneratorPort jwtGeneratorPort,
            UuidGeneratorPort uuidGeneratorPort,
            ClockPort clockPort,
            EventPublisherPort eventPublisherPort) {
        this.sessionRepository = sessionRepository;
        this.passwordHasherPort = passwordHasherPort;
        this.jwtGeneratorPort = jwtGeneratorPort;
        this.uuidGeneratorPort = uuidGeneratorPort;
        this.clockPort = clockPort;
        this.eventPublisherPort = eventPublisherPort;
    }

    public TokenPairDto execute(RefreshTokenCommand command) {
        RefreshTokenValidator.validate(command);

        Session session = sessionRepository.findById(SessionId.of(command.sessionId()))
                .orElseThrow(() -> new SessionOperationException("Session not found."));

        var newTokenId = uuidGeneratorPort.generate();
        String rawNewRefreshToken = uuidGeneratorPort.generate().toString();
        String newTokenHash = passwordHasherPort.hash(rawNewRefreshToken);
        var newExpiry = clockPort.now().plus(REFRESH_TOKEN_TTL);

        session.rotateRefreshToken(newTokenId, newTokenHash, newExpiry);
        sessionRepository.save(session);

        String accessToken = jwtGeneratorPort.generateAccessToken(
                session.getUserId().value(),
                session.getSessionId().value(),
                Map.of());

        eventPublisherPort.publishAll(session.getDomainEvents());
        session.clearDomainEvents();

        return new TokenPairDto(accessToken, rawNewRefreshToken, jwtGeneratorPort.getAccessTokenTtlSeconds());
    }
}
