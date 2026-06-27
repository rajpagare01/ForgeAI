package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.ActivateAccountCommand;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Administrative action to activate a suspended or locked user account.
 */
public final class ActivateAccountUseCase {

    private final UserRepository userRepository;
    private final EventPublisherPort eventPublisherPort;

    public ActivateAccountUseCase(
            UserRepository userRepository,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.eventPublisherPort = eventPublisherPort;
    }

    public void execute(ActivateAccountCommand command) {
        User user = userRepository.findById(UserId.of(command.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));

        user.activateAccount();
        userRepository.save(user);

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();
    }
}
