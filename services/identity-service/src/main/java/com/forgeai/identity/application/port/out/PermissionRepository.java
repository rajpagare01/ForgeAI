package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.Permission;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PermissionRepository {
    Optional<Permission> findById(UUID id);
    Optional<Permission> findByCode(String code);
    List<Permission> findAll();
}
