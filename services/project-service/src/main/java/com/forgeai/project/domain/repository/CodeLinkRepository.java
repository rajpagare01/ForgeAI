package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeLinkRepository {
    CodeLink save(CodeLink codeLink);
    Optional<CodeLink> findById(UUID id);
    Optional<CodeLink> findByIdAndOrganizationId(UUID id, UUID organizationId);
    List<CodeLink> findAllByOrganizationIdAndTaskId(UUID organizationId, UUID taskId);
    List<CodeLink> findAllByTaskId(UUID taskId);
    List<CodeLink> findAllByLinkTypeAndExternalRef(CodeLinkType linkType, String externalRef);
    boolean existsByTaskIdAndLinkTypeAndExternalRef(UUID taskId, CodeLinkType linkType, String externalRef);
}

