package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.ForgotPasswordCommand;
import com.forgeai.identity.application.port.EmailSenderPort;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.entity.PasswordResetToken;
import com.forgeai.identity.domain.event.PasswordResetRequestedEvent;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.service.TokenGenerationService;
import com.forgeai.identity.domain.valueobject.Email;

/**
 * Initiates the password-reset flow. If the email exists, a reset token
 * is generated and sent. If not, the operation silently succeeds
 * to prevent email enumeration attacks.
 */
public final class ForgotPasswordUseCase {

    private final UserRepository userRepository;
    private final TokenGenerationService tokenGenerationService;
    private final EmailSenderPort emailSenderPort;
    private final EventPublisherPort eventPublisherPort;

    public ForgotPasswordUseCase(
            UserRepository userRepository,
            TokenGenerationService tokenGenerationService,
            EmailSenderPort emailSenderPort,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.tokenGenerationService = tokenGenerationService;
        this.emailSenderPort = emailSenderPort;
        this.eventPublisherPort = eventPublisherPort;
    }

    public void execute(ForgotPasswordCommand command) {
        Email email = Email.of(command.email());

        userRepository.findByEmail(email).ifPresent(user -> {
            PasswordResetToken resetToken = tokenGenerationService.generatePasswordResetToken();
            emailSenderPort.sendPasswordResetEmail(email.value(), resetToken.getToken());
            eventPublisherPort.publish(new PasswordResetRequestedEvent(user.getUserId().value()));
        });
        // Silently succeed even if user not found (prevent enumeration)
    }
}
