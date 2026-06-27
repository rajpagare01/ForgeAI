package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.command.RegisterUserCommand;
import com.forgeai.identity.application.dto.UserDto;
import com.forgeai.identity.application.exception.RegistrationFailedException;
import com.forgeai.identity.application.mapper.UserMapper;
import com.forgeai.identity.application.port.EmailSenderPort;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.application.port.PasswordHasherPort;
import com.forgeai.identity.application.validator.RegisterUserValidator;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.entity.EmailVerificationToken;
import com.forgeai.identity.domain.exception.UserAlreadyExistsException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.service.PasswordPolicyService;
import com.forgeai.identity.domain.service.TokenGenerationService;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.PasswordHash;

/**
 * Orchestrates the user registration flow:
 * <ol>
 *   <li>Validates input completeness</li>
 *   <li>Enforces password policy</li>
 *   <li>Checks for duplicate email</li>
 *   <li>Creates the User aggregate</li>
 *   <li>Generates and sends a verification email</li>
 *   <li>Publishes domain events</li>
 * </ol>
 */
public final class RegisterUserUseCase {

    private final UserRepository userRepository;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordHasherPort passwordHasherPort;
    private final TokenGenerationService tokenGenerationService;
    private final EmailSenderPort emailSenderPort;
    private final EventPublisherPort eventPublisherPort;

    public RegisterUserUseCase(
            UserRepository userRepository,
            PasswordPolicyService passwordPolicyService,
            PasswordHasherPort passwordHasherPort,
            TokenGenerationService tokenGenerationService,
            EmailSenderPort emailSenderPort,
            EventPublisherPort eventPublisherPort) {
        this.userRepository = userRepository;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHasherPort = passwordHasherPort;
        this.tokenGenerationService = tokenGenerationService;
        this.emailSenderPort = emailSenderPort;
        this.eventPublisherPort = eventPublisherPort;
    }

    /**
     * Executes the user registration use case.
     *
     * @param command the registration command
     * @return a DTO representing the newly created user
     * @throws RegistrationFailedException if the email is already taken
     */
    public UserDto execute(RegisterUserCommand command) {
        RegisterUserValidator.validate(command);

        passwordPolicyService.validate(command.password());

        Email email = Email.of(command.email());

        if (userRepository.existsByEmail(email)) {
            throw new RegistrationFailedException("An account with this email already exists.");
        }

        String hashedPassword = passwordHasherPort.hash(command.password());
        PasswordHash passwordHash = PasswordHash.of(hashedPassword);

        User user = User.register(email, passwordHash);
        userRepository.save(user);

        EmailVerificationToken verificationToken = tokenGenerationService.generateEmailVerificationToken();
        emailSenderPort.sendVerificationEmail(email.value(), verificationToken.getToken());

        eventPublisherPort.publishAll(user.getDomainEvents());
        user.clearDomainEvents();

        return UserMapper.toDto(user);
    }
}
