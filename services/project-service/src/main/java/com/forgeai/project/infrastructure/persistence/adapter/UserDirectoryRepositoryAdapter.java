package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.UserDirectory;
import com.forgeai.project.domain.repository.UserDirectoryRepository;
import com.forgeai.project.infrastructure.persistence.entity.UserDirectoryJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataUserDirectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserDirectoryRepositoryAdapter implements UserDirectoryRepository {

    private final SpringDataUserDirectoryRepository springDataRepository;

    @Override
    public UserDirectory save(UserDirectory userDirectory) {
        UserDirectoryJpaEntity entity = EntityDomainMapper.toEntity(userDirectory);
        UserDirectoryJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<UserDirectory> findById(UUID id) {
        return springDataRepository.findById(id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<UserDirectory> findByEmail(String email) {
        return springDataRepository.findByEmail(email)
                .map(EntityDomainMapper::toDomain);
    }
}
