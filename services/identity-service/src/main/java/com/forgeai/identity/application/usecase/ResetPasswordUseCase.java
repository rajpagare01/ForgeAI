package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.ResetPasswordCommand;
import com.forgeai.identity.application.exception.ApplicationException;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.port.PasswordHasherPort;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.event.PasswordResetCompletedEvent;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.service.PasswordPolicyService;
import com.forgeai.identity.domain.valueobject.PasswordHash;
import com.forgeai.identity.domain.valueobject.UserId;

import java.util.UUID;

/**
 * Completes the password-reset flow by validating the token,
 * enforcing the password policy, and updating the user's credential.
 */
public final class ResetPasswordUseCase {

    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordHasherPort passwordHasherPort;
    private final EventPublisherPort eventPublisherPort;

    public ResetPasswordUseCase(
            UserRepository userRepository,
            PasswordPolicyService passwordPolicyService,
            PasswordHasherPort passwordHasherPort,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHasherPort = passwordHasherPort;
        this.eventPublisherPort = eventPublisherPort;
    }

    /**
     * @param command the reset command
     * @param userId  the user ID resolved from the reset token (by infrastructure)
     */
    public void execute(ResetPasswordCommand command, UUID userId) {
        passwordPolicyService.validate(command.newPassword());

        User user = userRepository.findById(UserId.of(userId))
                .orElseThrow(() -> new ResetFailedException("User not found."));

        String hashed = passwordHasherPort.hash(command.newPassword());
        user.changePassword(PasswordHash.of(hashed));
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();
        eventPublisherPort.publish(new PasswordResetCompletedEvent(userId));
    }

    private static final class ResetFailedException extends ApplicationException {
        ResetFailedException(String message) { super("PASSWORD_RESET_FAILED", message); }
    }
}
