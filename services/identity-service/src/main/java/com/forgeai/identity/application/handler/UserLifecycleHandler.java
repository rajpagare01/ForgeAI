package com.forgeai.identity.application.handler;

import com.forgeai.identity.application.command.ActivateAccountCommand;
import com.forgeai.identity.application.command.LockAccountCommand;
import com.forgeai.identity.application.command.SuspendAccountCommand;
import com.forgeai.identity.application.command.UnlockAccountCommand;
import com.forgeai.identity.application.command.VerifyEmailCommand;
import com.forgeai.identity.application.port.TransactionManagerPort;
import com.forgeai.identity.application.usecase.ActivateAccountUseCase;
import com.forgeai.identity.application.usecase.LockAccountUseCase;
import com.forgeai.identity.application.usecase.SuspendAccountUseCase;
import com.forgeai.identity.application.usecase.UnlockAccountUseCase;
import com.forgeai.identity.application.usecase.VerifyEmailUseCase;

import java.util.UUID;

/**
 * Orchestrates administrative and lifecycle flows for users.
 */
public final class UserLifecycleHandler {

    private final VerifyEmailUseCase verifyEmailUseCase;
    private final LockAccountUseCase lockAccountUseCase;
    private final UnlockAccountUseCase unlockAccountUseCase;
    private final SuspendAccountUseCase suspendAccountUseCase;
    private final ActivateAccountUseCase activateAccountUseCase;
    private final TransactionManagerPort transactionManager;

    public UserLifecycleHandler(
            VerifyEmailUseCase verifyEmailUseCase,
            LockAccountUseCase lockAccountUseCase,
            UnlockAccountUseCase unlockAccountUseCase,
            SuspendAccountUseCase suspendAccountUseCase,
            ActivateAccountUseCase activateAccountUseCase,
            TransactionManagerPort transactionManager) {
        this.verifyEmailUseCase = verifyEmailUseCase;
        this.lockAccountUseCase = lockAccountUseCase;
        this.unlockAccountUseCase = unlockAccountUseCase;
        this.suspendAccountUseCase = suspendAccountUseCase;
        this.activateAccountUseCase = activateAccountUseCase;
        this.transactionManager = transactionManager;
    }

    public void verifyEmail(VerifyEmailCommand command, UUID resolvedUserId) {
        transactionManager.executeInTransaction(() ->
                verifyEmailUseCase.execute(command, resolvedUserId)
        );
    }

    public void lockAccount(LockAccountCommand command) {
        transactionManager.executeInTransaction(() ->
                lockAccountUseCase.execute(command)
        );
    }

    public void unlockAccount(UnlockAccountCommand command) {
        transactionManager.executeInTransaction(() ->
                unlockAccountUseCase.execute(command)
        );
    }

    public void suspendAccount(SuspendAccountCommand command) {
        transactionManager.executeInTransaction(() ->
                suspendAccountUseCase.execute(command)
        );
    }

    public void activateAccount(ActivateAccountCommand command) {
        transactionManager.executeInTransaction(() ->
                activateAccountUseCase.execute(command)
        );
    }
}
