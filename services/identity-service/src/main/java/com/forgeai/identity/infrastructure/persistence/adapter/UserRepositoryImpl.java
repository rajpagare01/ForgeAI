package com.forgeai.identity.infrastructure.persistence.adapter;

import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.UserJpaEntity;
import com.forgeai.identity.infrastructure.persistence.mapper.UserPersistenceMapper;
import com.forgeai.identity.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;
    private final UserPersistenceMapper mapper;

    public UserRepositoryImpl(UserJpaRepository jpaRepository, UserPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public void save(User user) {
        UserJpaEntity entity = mapper.toEntity(user);
        jpaRepository.save(entity);
    }

    @Override
    public Optional<User> findById(UserId userId) {
        return jpaRepository.findById(userId.value())
                .map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return jpaRepository.findByEmail(email.value())
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return jpaRepository.existsByEmail(email.value());
    }

    @Override
    public void delete(UserId userId) {
        jpaRepository.deleteById(userId.value());
    }
}
