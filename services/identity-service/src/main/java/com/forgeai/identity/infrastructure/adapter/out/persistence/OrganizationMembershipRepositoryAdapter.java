package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.OrganizationMembershipRepository;
import com.forgeai.identity.domain.model.OrganizationMembership;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OrganizationMembershipJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.OrganizationMembershipMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OrganizationMembershipRepositoryAdapter implements OrganizationMembershipRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OrganizationMembershipRepository jpaRepository;
    private final OrganizationMembershipMapper mapper;

    @Override
    public Optional<OrganizationMembership> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public OrganizationMembership save(OrganizationMembership entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    
    @Override
    public java.util.Optional<OrganizationMembership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId) {
        return jpaRepository.findByUserIdAndOrganizationId(userId, organizationId).map(mapper::toDomain);
    }
    
    @Override
    public List<OrganizationMembership> findByOrganizationId(UUID organizationId) {
        return jpaRepository.findByOrganizationId(organizationId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
    
    @Override
    public List<OrganizationMembership> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public long countByOrganizationIdAndRoleId(UUID organizationId, UUID roleId) {
        return jpaRepository.countByOrganizationIdAndRoleId(organizationId, roleId);
    }

}

