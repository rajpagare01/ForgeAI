package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.OrganizationRepository;
import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.OrganizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrganizationRepositoryAdapter implements OrganizationRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OrganizationRepository jpaRepository;
    private final OrganizationMapper mapper;

    @Override
    public Optional<Organization> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Organization> findBySlug(OrganizationSlug slug) {
        return jpaRepository.findBySlug(slug.value()).map(mapper::toDomain);
    }

    @Override
    public Organization save(Organization entity) {
        OrganizationJpaEntity jpaEntity = mapper.toEntity(entity);
        OrganizationJpaEntity savedEntity = jpaRepository.save(jpaEntity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }
}
