package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.UnlockAccountCommand;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Administrative action to unlock a user account.
 */
public final class UnlockAccountUseCase {

    private final UserRepository userRepository;
    private final EventPublisherPort eventPublisherPort;

    public UnlockAccountUseCase(
            UserRepository userRepository,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.eventPublisherPort = eventPublisherPort;
    }

    public void execute(UnlockAccountCommand command) {
        User user = userRepository.findById(UserId.of(command.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));

        user.unlockAccount();
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();
    }
}
