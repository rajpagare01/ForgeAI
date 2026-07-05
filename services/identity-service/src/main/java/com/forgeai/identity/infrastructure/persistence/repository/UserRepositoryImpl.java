package com.forgeai.identity.infrastructure.persistence.repository;

import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.repository.UserRepository;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.UserJpaEntity;
import com.forgeai.identity.infrastructure.persistence.exception.PersistenceExceptionTranslator;
import com.forgeai.identity.infrastructure.persistence.mapper.UserPersistenceMapper;
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
        try {
            UserJpaEntity entity = mapper.toEntity(user);
            jpaRepository.save(entity);
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public Optional<User> findById(UserId userId) {
        try {
            return jpaRepository.findById(userId.value())
                    .map(mapper::toDomain);
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        try {
            return jpaRepository.findByEmail(email.value())
                    .map(mapper::toDomain);
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public boolean existsByEmail(Email email) {
        try {
            return jpaRepository.existsByEmail(email.value());
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }

    @Override
    public void delete(UserId userId) {
        try {
            jpaRepository.deleteById(userId.value());
        } catch (Exception e) {
            throw PersistenceExceptionTranslator.translate(e);
        }
    }
}
