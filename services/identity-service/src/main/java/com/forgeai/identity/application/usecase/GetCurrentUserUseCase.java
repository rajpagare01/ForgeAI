package com.forgeai.identity.application.usecase;

import com.forgeai.identity.application.dto.CurrentUserDto;
import com.forgeai.identity.application.mapper.UserMapper;
import com.forgeai.identity.application.query.GetCurrentUserQuery;
import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.exception.UserNotFoundException;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.UserId;

/**
 * Retrieves the profile of the currently authenticated user.
 */
public final class GetCurrentUserUseCase {

    private final UserRepository userRepository;

    public GetCurrentUserUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public CurrentUserDto execute(GetCurrentUserQuery query) {
        User user = userRepository.findById(UserId.of(query.userId()))
                .orElseThrow(() -> new UserNotFoundException("User not found."));
        
        return UserMapper.toCurrentUserDto(user);
    }
}
