package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.LockAccountCommand;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Administrative action to lock a user account.
 */
public final class LockAccountUseCase {

    private final UserRepository userRepository;
    private final EventPublisherPort eventPublisherPort;

    public LockAccountUseCase(
            UserRepository userRepository,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.eventPublisherPort = eventPublisherPort;
    }

    public void execute(LockAccountCommand command) {
        User user = userRepository.findById(UserId.of(command.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));

        user.lockAccount();
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();
    }
}
