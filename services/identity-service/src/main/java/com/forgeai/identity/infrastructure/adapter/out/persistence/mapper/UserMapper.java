package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.User;
import com.forgeai.identity.domain.model.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toDomain(UserJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        User user = new User();
        user.setId(entity.getId());
        user.setEmail(new Email(entity.getEmail()));
        if (entity.getPendingEmail() != null) {
            user.setPendingEmail(new Email(entity.getPendingEmail()));
        }
        user.setUsername(new Username(entity.getUsername()));
        user.setPasswordHash(entity.getPasswordHash());
        user.setFirstName(entity.getFirstName());
        user.setLastName(entity.getLastName());
        
        if (entity.getStatus() != null) {
            user.setStatus(UserStatus.valueOf(entity.getStatus()));
        }
        
        user.setEmailVerified(entity.isEmailVerified());
        user.setCreatedAt(entity.getCreatedAt());
        user.setUpdatedAt(entity.getUpdatedAt());

        return user;
    }

    public UserJpaEntity toEntity(User domain) {
        if (domain == null) {
            return null;
        }

        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(domain.getId());
        if (domain.getEmail() != null) {
            entity.setEmail(domain.getEmail().value());
        }
        if (domain.getPendingEmail() != null) {
            entity.setPendingEmail(domain.getPendingEmail().value());
        }
        if (domain.getUsername() != null) {
            entity.setUsername(domain.getUsername().value());
        }
        entity.setPasswordHash(domain.getPasswordHash());
        entity.setFirstName(domain.getFirstName());
        entity.setLastName(domain.getLastName());
        
        if (domain.getStatus() != null) {
            entity.setStatus(domain.getStatus().name());
        }
        
        entity.setEmailVerified(domain.isEmailVerified());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());

        return entity;
    }
}
