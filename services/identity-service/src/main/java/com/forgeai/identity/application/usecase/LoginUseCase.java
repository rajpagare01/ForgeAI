package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.LoginCommand;
import com.forgeai.identity.application.dto.AuthenticationResultDto;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.application.exception.AuthenticationFailedException;
import com.forgeai.identity.application.mapper.AuthenticationMapper;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.port.JwtGeneratorPort;
import com.forgeai.identity.application.port.PasswordHasherPort;
import com.forgeai.identity.application.port.UuidGeneratorPort;
import com.forgeai.identity.application.port.ClockPort;
import com.forgeai.identity.application.port.AuditLoggerPort;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.entity.RefreshToken;
import com.forgeai.identity.domain.event.UserLoggedInEvent;
import com.forgeai.identity.domain.repository.SessionRepository;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.DeviceInfo;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.IpAddress;
import com.forgeai.identity.domain.valueobject.UserAgent;

import java.time.Duration;
import java.util.Map;

/**
 * Orchestrates the user login flow:
 * <ol>
 *   <li>Looks up user by email</li>
 *   <li>Verifies password against stored hash</li>
 *   <li>Asserts the account is eligible for login</li>
 *   <li>Creates a new session with a refresh token</li>
 *   <li>Issues a JWT access token</li>
 *   <li>Publishes domain events and audit log</li>
 * </ol>
 */
public final class LoginUseCase {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordHasherPort passwordHasherPort;
    private final JwtGeneratorPort jwtGeneratorPort;
    private final UuidGeneratorPort uuidGeneratorPort;
    private final ClockPort clockPort;
    private final EventPublisherPort eventPublisherPort;
    private final AuditLoggerPort auditLoggerPort;

    public LoginUseCase(
            UserRepository userRepository,
            SessionRepository sessionRepository,
            PasswordHasherPort passwordHasherPort,
            JwtGeneratorPort jwtGeneratorPort,
            UuidGeneratorPort uuidGeneratorPort,
            ClockPort clockPort,
            EventPublisherPort eventPublisherPort,
            AuditLoggerPort auditLoggerPort) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordHasherPort = passwordHasherPort;
        this.jwtGeneratorPort = jwtGeneratorPort;
        this.uuidGeneratorPort = uuidGeneratorPort;
        this.clockPort = clockPort;
        this.eventPublisherPort = eventPublisherPort;
        this.auditLoggerPort = auditLoggerPort;
    }

    /**
     * Executes the login use case.
     *
     * @param command the login command containing credentials and metadata
     * @return the authentication result with tokens
     * @throws AuthenticationFailedException if credentials are invalid or account is ineligible
     */
    public AuthenticationResultDto execute(LoginCommand command) {
        Email email = Email.of(command.email());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password."));

        if (!passwordHasherPort.verify(command.password(), user.getPasswordHash().value())) {
            throw new AuthenticationFailedException("Invalid email or password.");
        }

        user.assertCanLogin();

        // Create refresh token
        var refreshTokenId = uuidGeneratorPort.generate();
        String rawRefreshToken = uuidGeneratorPort.generate().toString();
        String refreshTokenHash = passwordHasherPort.hash(rawRefreshToken);
        var refreshTokenExpiry = clockPort.now().plus(REFRESH_TOKEN_TTL);
        RefreshToken refreshToken = new RefreshToken(refreshTokenId, refreshTokenHash, refreshTokenExpiry);

        // Create session
        Session session = Session.create(
                user.getUserId(),
                DeviceInfo.of(command.deviceInfo()),
                IpAddress.of(command.ipAddress()),
                UserAgent.of(command.userAgent()),
                refreshToken);

        sessionRepository.save(session);

        // Generate JWT
        String accessToken = jwtGeneratorPort.generateAccessToken(
                user.getUserId().value(),
                session.getSessionId().value(),
                Map.of("email", user.getEmail().value()));

        TokenPairDto tokenPair = new TokenPairDto(
                accessToken,
                rawRefreshToken,
                jwtGeneratorPort.getAccessTokenTtlSeconds());

        // Publish events
        eventPublisherPort.publishAll(session.getDomainEvents());
        session.clearDomainEvents();
        eventPublisherPort.publish(new UserLoggedInEvent(user.getUserId().value()));

        auditLoggerPort.log(user.getUserId().value(), "USER_LOGIN",
                "Login from " + command.ipAddress() + " via " + command.deviceInfo());

        return AuthenticationMapper.toResult(user, session, tokenPair);
    }
}
