package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.TeamMembershipRepository;
import com.forgeai.identity.domain.model.TeamMembership;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.TeamMembershipJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.TeamMembershipMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TeamMembershipRepositoryAdapter implements TeamMembershipRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.TeamMembershipRepository jpaRepository;
    private final TeamMembershipMapper mapper;

    @Override
    public Optional<TeamMembership> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public TeamMembership save(TeamMembership entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    
    @Override
    public java.util.Optional<TeamMembership> findByTeamIdAndUserId(UUID teamId, UUID userId) {
        return jpaRepository.findByTeamIdAndUserId(teamId, userId).map(mapper::toDomain);
    }
    @Override
    public List<TeamMembership> findByTeamId(UUID teamId) {
        return jpaRepository.findByTeamId(teamId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
    @Override
    public List<TeamMembership> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

}

