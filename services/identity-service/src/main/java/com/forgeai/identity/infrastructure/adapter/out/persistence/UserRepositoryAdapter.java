package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.UserRepository;
import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements com.forgeai.identity.application.port.out.UserRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return userRepository.findByEmailIgnoreCase(email.value())
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(Username username) {
        return userRepository.findByUsernameIgnoreCase(username.value())
                .map(userMapper::toDomain);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = userMapper.toEntity(user);
        UserJpaEntity saved = userRepository.save(entity);
        return userMapper.toDomain(saved);
    }
}
