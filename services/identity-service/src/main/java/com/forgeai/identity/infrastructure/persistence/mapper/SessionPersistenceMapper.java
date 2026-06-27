package com.forgeai.identity.infrastructure.persistence.mapper;

import com.forgeai.identity.domain.aggregate.Session;
import com.forgeai.identity.domain.aggregate.SessionStatus;
import com.forgeai.identity.domain.entity.RefreshToken;
import com.forgeai.identity.domain.valueobject.DeviceInfo;
import com.forgeai.identity.domain.valueobject.IpAddress;
import com.forgeai.identity.domain.valueobject.SessionId;
import com.forgeai.identity.domain.valueobject.UserAgent;
import com.forgeai.identity.domain.valueobject.UserId;
import com.forgeai.identity.infrastructure.persistence.entity.SessionJpaEntity;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;

@Component
public class SessionPersistenceMapper {

    private final RefreshTokenMapper refreshTokenMapper;

    public SessionPersistenceMapper(RefreshTokenMapper refreshTokenMapper) {
        this.refreshTokenMapper = refreshTokenMapper;
    }

    public SessionJpaEntity toEntity(Session domain) {
        return new SessionJpaEntity(
                domain.getSessionId().value(),
                domain.getUserId().value(),
                domain.getDeviceInfo().value(),
                domain.getIpAddress().value(),
                domain.getUserAgent().value(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getLastAccessed()
        );
    }

    public Session toDomain(SessionJpaEntity entity, RefreshToken refreshToken) {
        try {
            Constructor<Session> constructor = Session.class.getDeclaredConstructor(
                    SessionId.class, UserId.class, DeviceInfo.class, IpAddress.class, UserAgent.class, RefreshToken.class);
            constructor.setAccessible(true);
            
            Session session = constructor.newInstance(
                    SessionId.of(entity.getId()),
                    UserId.of(entity.getUserId()),
                    DeviceInfo.of(entity.getDeviceInfo()),
                    IpAddress.of(entity.getIpAddress()),
                    UserAgent.of(entity.getUserAgent()),
                    refreshToken
            );
            
            setField(session, "status", SessionStatus.valueOf(entity.getStatus()));
            setField(session, "createdAt", entity.getCreatedAt());
            setField(session, "lastAccessed", entity.getLastAccessed());
            
            return session;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map SessionJpaEntity to Session domain aggregate", e);
        }
    }
    
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
