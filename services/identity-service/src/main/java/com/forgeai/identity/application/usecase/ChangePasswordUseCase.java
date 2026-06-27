package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.ChangePasswordCommand;
import com.forgeai.identity.application.exception.AuthenticationFailedException;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.port.PasswordHasherPort;
import com.forgeai.identity.application.port.AuditLoggerPort;
import com.forgeai.identity.application.validator.PasswordChangeValidator;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.service.PasswordPolicyService;
import com.forgeai.identity.domain.valueobject.PasswordHash;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Changes the password for an authenticated user after verifying
 * the current password and enforcing the password policy on the new one.
 */
public final class ChangePasswordUseCase {

    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordHasherPort passwordHasherPort;
    private final EventPublisherPort eventPublisherPort;
    private final AuditLoggerPort auditLoggerPort;

    public ChangePasswordUseCase(
            UserRepository userRepository,
            PasswordPolicyService passwordPolicyService,
            PasswordHasherPort passwordHasherPort,
            EventPublisherPort eventPublisherPort,
            AuditLoggerPort auditLoggerPort) {
        this.userRepository = userRepository;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHasherPort = passwordHasherPort;
        this.eventPublisherPort = eventPublisherPort;
        this.auditLoggerPort = auditLoggerPort;
    }

    public void execute(ChangePasswordCommand command) {
        PasswordChangeValidator.validate(command);

        User user = userRepository.findById(UserId.of(command.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));

        if (!passwordHasherPort.verify(command.oldPassword(), user.getPasswordHash().value())) {
            throw new AuthenticationFailedException("Current password is incorrect.");
        }

        passwordPolicyService.validate(command.newPassword());

        String hashed = passwordHasherPort.hash(command.newPassword());
        user.changePassword(PasswordHash.of(hashed));
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();

        auditLoggerPort.log(command.userId(), "PASSWORD_CHANGED", "Password changed by user.");
    }
}
