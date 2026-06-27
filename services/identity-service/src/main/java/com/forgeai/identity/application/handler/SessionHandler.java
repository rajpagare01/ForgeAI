package com.forgeai.identity.application.handler;

import com.forgeai.identity.application.command.LogoutCommand;
import com.forgeai.identity.application.command.RefreshTokenCommand;
import com.forgeai.identity.application.command.RevokeSessionCommand;
import com.forgeai.identity.application.dto.SessionDto;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.application.port.TransactionManagerPort;
import com.forgeai.identity.application.query.GetUserSessionsQuery;
import com.forgeai.identity.application.usecase.GetUserSessionsUseCase;
import com.forgeai.identity.application.usecase.LogoutUseCase;
import com.forgeai.identity.application.usecase.RefreshTokenUseCase;

import java.util.List;

/**
 * Orchestrates session-related flows.
 */
public final class SessionHandler {

    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final GetUserSessionsUseCase getUserSessionsUseCase;
    private final TransactionManagerPort transactionManager;

    public SessionHandler(
            RefreshTokenUseCase refreshTokenUseCase,
            LogoutUseCase logoutUseCase,
            GetUserSessionsUseCase getUserSessionsUseCase,
            TransactionManagerPort transactionManager) {
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.getUserSessionsUseCase = getUserSessionsUseCase;
        this.transactionManager = transactionManager;
    }

    public TokenPairDto refreshToken(RefreshTokenCommand command) {
        return transactionManager.executeInTransaction(() ->
                refreshTokenUseCase.execute(command)
        );
    }

    public void logout(LogoutCommand command) {
        transactionManager.executeInTransaction(() ->
                logoutUseCase.execute(command)
        );
    }
    
    public List<SessionDto> getUserSessions(GetUserSessionsQuery query) {
        // Reads often do not need full RW transactions, but we use the manager for consistency
        return transactionManager.executeInTransaction(() ->
                getUserSessionsUseCase.execute(query)
        );
    }
}
