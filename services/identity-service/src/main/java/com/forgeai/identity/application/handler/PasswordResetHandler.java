package com.forgeai.identity.application.handler;

import com.forgeai.identity.application.command.ForgotPasswordCommand;
import com.forgeai.identity.application.command.ResetPasswordCommand;
import com.forgeai.identity.application.port.TransactionManagerPort;
import com.forgeai.identity.application.usecase.ForgotPasswordUseCase;
import com.forgeai.identity.application.usecase.ResetPasswordUseCase;

import java.util.UUID;

/**
 * Orchestrates password reset flows.
 */
public final class PasswordResetHandler {

    private final ForgotPasswordUseCase forgotPasswordUseCase;
    private final ResetPasswordUseCase resetPasswordUseCase;
    private final TransactionManagerPort transactionManager;

    public PasswordResetHandler(
            ForgotPasswordUseCase forgotPasswordUseCase,
            ResetPasswordUseCase resetPasswordUseCase,
            TransactionManagerPort transactionManager) {
        this.forgotPasswordUseCase = forgotPasswordUseCase;
        this.resetPasswordUseCase = resetPasswordUseCase;
        this.transactionManager = transactionManager;
    }

    public void requestPasswordReset(ForgotPasswordCommand command) {
        transactionManager.executeInTransaction(() ->
                forgotPasswordUseCase.execute(command)
        );
    }

    public void resetPassword(ResetPasswordCommand command, UUID resolvedUserId) {
        transactionManager.executeInTransaction(() ->
                resetPasswordUseCase.execute(command, resolvedUserId)
        );
    }
}
