package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;
import com.forgeai.project.domain.repository.CodeLinkRepository;
import com.forgeai.project.infrastructure.persistence.entity.CodeLinkJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataCodeLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class CodeLinkRepositoryAdapter implements CodeLinkRepository {

    private final SpringDataCodeLinkRepository springDataRepository;

    @Override
    public CodeLink save(CodeLink codeLink) {
        CodeLinkJpaEntity entity = EntityDomainMapper.toEntity(codeLink);
        CodeLinkJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<CodeLink> findById(UUID id) {
        return springDataRepository.findById(id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<CodeLink> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return springDataRepository.findByIdAndOrganizationId(id, organizationId)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<CodeLink> findAllByOrganizationIdAndTaskId(UUID organizationId, UUID taskId) {
        return springDataRepository.findAllByOrganizationIdAndTaskId(organizationId, taskId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<CodeLink> findAllByTaskId(UUID taskId) {
        return springDataRepository.findAllByTaskId(taskId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }


    @Override
    public boolean existsByTaskIdAndLinkTypeAndExternalRef(UUID taskId, CodeLinkType linkType, String externalRef) {
        return springDataRepository.existsByTaskIdAndLinkTypeAndExternalRef(taskId, linkType, externalRef);
    }
}
