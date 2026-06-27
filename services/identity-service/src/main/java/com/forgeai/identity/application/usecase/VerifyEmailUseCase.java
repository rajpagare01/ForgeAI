package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.VerifyEmailCommand;
import com.forgeai.identity.application.exception.ApplicationException;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.validator.EmailVerificationValidator;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

import java.util.UUID;

/**
 * Verifies a user's email using the provided one-time token
 * and transitions the account from PENDING_VERIFICATION to ACTIVE.
 */
public final class VerifyEmailUseCase {

    private final UserRepository userRepository;
    private final EventPublisherPort eventPublisherPort;

    public VerifyEmailUseCase(
            UserRepository userRepository,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.eventPublisherPort = eventPublisherPort;
    }

    /**
     * Executes the email verification use case.
     * In a full implementation the token would be looked up from a token store
     * to resolve the userId. Here we accept the userId embedded in the token payload.
     *
     * @param command the verification command
     * @param userId  the user ID resolved from the token (by infrastructure)
     */
    public void execute(VerifyEmailCommand command, UUID userId) {
        EmailVerificationValidator.validate(command);

        User user = userRepository.findById(UserId.of(userId))
                .orElseThrow(() -> new VerificationFailedException("User not found."));

        user.verifyEmail();
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();
    }

    private static final class VerificationFailedException extends ApplicationException {
        VerificationFailedException(String message) { super("VERIFICATION_FAILED", message); }
    }
}
