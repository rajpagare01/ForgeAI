package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.SuspendAccountCommand;
import com.forgeai.identity.application.port.AuditLoggerPort;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Administrative action to suspend a user account.
 */
public final class SuspendAccountUseCase {

    private final UserRepository userRepository;
    private final EventPublisherPort eventPublisherPort;
    private final AuditLoggerPort auditLoggerPort;

    public SuspendAccountUseCase(
            UserRepository userRepository,
            EventPublisherPort eventPublisherPort,
            AuditLoggerPort auditLoggerPort) {
        this.userRepository = userRepository;
        this.eventPublisherPort = eventPublisherPort;
        this.auditLoggerPort = auditLoggerPort;
    }

    public void execute(SuspendAccountCommand command) {
        User user = userRepository.findById(UserId.of(command.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));

        user.suspendAccount();
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();

        auditLoggerPort.log(command.userId(), "ACCOUNT_SUSPENDED", "Reason: " + command.reason());
    }
}
