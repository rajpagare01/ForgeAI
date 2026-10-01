package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.TeamRepository;
import com.forgeai.identity.domain.model.Team;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.TeamMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TeamRepositoryAdapter implements TeamRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.TeamRepository jpaRepository;
    private final TeamMapper mapper;

    @Override
    public Optional<Team> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Team save(Team entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    
    @Override
    public List<Team> findByOrganizationId(UUID organizationId) {
        return jpaRepository.findByOrganizationId(organizationId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
    @Override
    public java.util.Optional<Team> findByOrganizationIdAndName(UUID organizationId, String name) {
        return jpaRepository.findByOrganizationIdAndName(organizationId, name).map(mapper::toDomain);
    }
    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

}

