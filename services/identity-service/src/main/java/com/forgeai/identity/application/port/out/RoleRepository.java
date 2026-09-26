package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Role;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface RoleRepository {
    Optional<Role> findById(UUID id);
    List<Role> findByOrganizationId(UUID organizationId);
    Optional<Role> findSystemRoleByName(String name);
    Optional<Role> findByOrganizationIdAndName(UUID organizationId, String name);
    Role save(Role entity);
    void deleteById(UUID id);
}
