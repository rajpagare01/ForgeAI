package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.TeamMembership;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface TeamMembershipRepository {
    Optional<TeamMembership> findById(UUID id);
    Optional<TeamMembership> findByTeamIdAndUserId(UUID teamId, UUID userId);
    List<TeamMembership> findByTeamId(UUID teamId);
    List<TeamMembership> findByUserId(UUID userId);
    TeamMembership save(TeamMembership entity);
    void deleteById(UUID id);
}
