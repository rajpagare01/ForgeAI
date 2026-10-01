package com.forgeai.identity.application.service;

import com.forgeai.identity.api.dto.LoginResponse;
import com.forgeai.identity.application.port.in.AuthenticationUseCase;
import com.forgeai.identity.application.port.out.PasswordHasher;
import com.forgeai.identity.application.port.out.TokenProvider;
import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.exception.InvalidUserStateException;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService implements AuthenticationUseCase {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenProvider tokenProvider;

    @Override
    @Transactional
    public User register(String email, String username, String password, String firstName, String lastName) {
        return userService.createUser(email, username, password, firstName, lastName);
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(String identifier, String password) {
        User user = resolveUser(identifier);

        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidUserStateException("User account is not active");
        }

        String token = tokenProvider.generateToken(user.getId());
        return new LoginResponse(token, user.getId(), user.getUsername().value(), user.getEmail().value());
    }

    private User resolveUser(String identifier) {
        if (identifier != null && identifier.contains("@")) {
            return userRepository.findByEmail(new Email(identifier)).orElse(null);
        } else if (identifier != null) {
            return userRepository.findByUsername(new Username(identifier)).orElse(null);
        }
        return null;
    }
}
