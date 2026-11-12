package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.domain.model.RoleScope;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.stream.Collectors;

@Component
public class RoleMapper {
    private final PermissionMapper permissionMapper;

    public RoleMapper() {
        this(new PermissionMapper());
    }

    @Autowired
    public RoleMapper(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper != null ? permissionMapper : new PermissionMapper();
    }

    public Role toDomain(RoleJpaEntity entity) {
        if (entity == null) return null;
        Role domain = new Role();
        BeanUtils.copyProperties(entity, domain, "scope", "permissions");
        if (entity.getScope() != null && !entity.getScope().isBlank()) {
            domain.setScope(RoleScope.valueOf(entity.getScope()));
        }
        if (entity.getPermissions() != null) {
            domain.setPermissions(entity.getPermissions().stream()
                    .map(permissionMapper::toDomain)
                    .collect(Collectors.toSet()));
        } else {
            domain.setPermissions(new HashSet<>());
        }
        return domain;
    }

    public RoleJpaEntity toEntity(Role domain) {
        if (domain == null) return null;
        RoleJpaEntity entity = new RoleJpaEntity();
        BeanUtils.copyProperties(domain, entity, "scope", "permissions");
        entity.setScope(domain.getScope() != null ? domain.getScope().name() : null);
        if (domain.getPermissions() != null) {
            entity.setPermissions(domain.getPermissions().stream()
                    .map(permissionMapper::toEntity)
                    .collect(Collectors.toSet()));
        } else {
            entity.setPermissions(new HashSet<>());
        }
        return entity;
    }
}
