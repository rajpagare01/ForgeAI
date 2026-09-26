package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Organization;
import com.forgeai.identity.domain.valueobject.OrganizationSlug;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {
    Optional<Organization> findById(UUID id);
    Optional<Organization> findBySlug(OrganizationSlug slug);
    Organization save(Organization entity);
    void deleteById(UUID id);
}
