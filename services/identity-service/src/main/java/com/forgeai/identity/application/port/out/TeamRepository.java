package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Team;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface TeamRepository {
    Optional<Team> findById(UUID id);
    List<Team> findByOrganizationId(UUID organizationId);
    Optional<Team> findByOrganizationIdAndName(UUID organizationId, String name);
    Team save(Team entity);
    void deleteById(UUID id);
}
