package com.forgeai.identity.application.handler;

import com.forgeai.identity.application.command.LoginCommand;
import com.forgeai.identity.application.command.RegisterUserCommand;
import com.forgeai.identity.application.dto.AuthenticationResultDto;
import com.forgeai.identity.application.dto.UserDto;
import com.forgeai.identity.application.port.TransactionManagerPort;
import com.forgeai.identity.application.usecase.LoginUseCase;
import com.forgeai.identity.application.usecase.RegisterUserUseCase;

/**
 * Orchestrates authentication-related flows and wraps them in transactions.
 */
public final class AuthenticationHandler {

    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUseCase loginUseCase;
    private final TransactionManagerPort transactionManager;

    public AuthenticationHandler(
            RegisterUserUseCase registerUserUseCase,
            LoginUseCase loginUseCase,
            TransactionManagerPort transactionManager) {
        this.registerUserUseCase = registerUserUseCase;
        this.loginUseCase = loginUseCase;
        this.transactionManager = transactionManager;
    }

    public UserDto registerUser(RegisterUserCommand command) {
        return transactionManager.executeInTransaction(() ->
                registerUserUseCase.execute(command)
        );
    }

    public AuthenticationResultDto login(LoginCommand command) {
        return transactionManager.executeInTransaction(() ->
                loginUseCase.execute(command)
        );
    }
}
