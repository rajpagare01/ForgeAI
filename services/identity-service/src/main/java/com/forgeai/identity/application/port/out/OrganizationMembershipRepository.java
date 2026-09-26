package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.OrganizationMembership;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface OrganizationMembershipRepository {
    Optional<OrganizationMembership> findById(UUID id);
    Optional<OrganizationMembership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);
    List<OrganizationMembership> findByOrganizationId(UUID organizationId);
    List<OrganizationMembership> findByUserId(UUID userId);
    OrganizationMembership save(OrganizationMembership entity);
    void deleteById(UUID id);
    long countByOrganizationIdAndRoleId(UUID organizationId, UUID roleId);
}
