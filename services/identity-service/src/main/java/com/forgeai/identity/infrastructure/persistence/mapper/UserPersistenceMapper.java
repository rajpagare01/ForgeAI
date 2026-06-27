package com.forgeai.identity.infrastructure.persistence.mapper;

import com.forgeai.identity.domain.aggregate.User;
import com.forgeai.identity.domain.aggregate.UserStatus;
import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.PasswordHash;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;

/**
 * Bidirectional mapper between User domain aggregate and UserJpaEntity.
 */
@Component
public class UserPersistenceMapper {

    public UserJpaEntity toEntity(User domain) {
        return new UserJpaEntity(
                domain.getUserId().value(),
                domain.getEmail().value(),
                domain.getPasswordHash().value(),
                domain.getStatus().name(),
                domain.isMfaEnabled(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public User toDomain(UserJpaEntity entity) {
        try {
            // Using reflection to bypass business rules for reconstitution from DB
            Constructor<User> constructor = User.class.getDeclaredConstructor(UserId.class, Email.class, PasswordHash.class);
            constructor.setAccessible(true);
            
            UserId userId = UserId.of(entity.getId());
            Email email = Email.of(entity.getEmail());
            PasswordHash passwordHash = PasswordHash.of(entity.getPasswordHash());
            
            User user = constructor.newInstance(userId, email, passwordHash);
            
            setField(user, "status", UserStatus.valueOf(entity.getStatus()));
            setField(user, "mfaEnabled", entity.isMfaEnabled());
            setField(user, "createdAt", entity.getCreatedAt());
            setField(user, "updatedAt", entity.getUpdatedAt());
            
            return user;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map UserJpaEntity to User domain aggregate", e);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
