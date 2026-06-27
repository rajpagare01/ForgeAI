package com.forgeai.identity.application.mapper;

import com.forgeai.identity.application.dto.AuthenticationResultDto;
import com.forgeai.identity.application.dto.TokenPairDto;
import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.aggregate.User;

/**
 * Maps domain aggregates into authentication result DTOs
 * suitable for returning to the presentation layer.
 */
public final class AuthenticationMapper {

    private AuthenticationMapper() {
    }

    /**
     * Assembles an AuthenticationResultDto from the authenticated user,
     * the created session, and the generated token pair.
     *
     * @param user      the authenticated user aggregate
     * @param session   the newly created session aggregate
     * @param tokenPair the issued token pair
     * @return the complete authentication result
     */
    public static AuthenticationResultDto toResult(User user, Session session, TokenPairDto tokenPair) {
        return new AuthenticationResultDto(
                user.getUserId().value(),
                user.getEmail().value(),
                session.getSessionId().value(),
                tokenPair);
    }
}
