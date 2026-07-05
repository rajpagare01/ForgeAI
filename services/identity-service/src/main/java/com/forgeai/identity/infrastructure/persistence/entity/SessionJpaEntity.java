package com.forgeai.identity.infrastructure.persistence.entity;

import com.forgeai.identity.domain.aggregate.SessionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity mapping for the Session domain aggregate.
 */
@Entity
@Table(name = "sessions")
public class SessionJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "last_accessed", nullable = false)
    private Instant lastAccessed;

    @OneToOne(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private RefreshTokenJpaEntity refreshToken;

    protected SessionJpaEntity() {
        // JPA constructor
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public Instant getLastAccessed() { return lastAccessed; }
    public void setLastAccessed(Instant lastAccessed) { this.lastAccessed = lastAccessed; }

    public RefreshTokenJpaEntity getRefreshToken() { return refreshToken; }
    public void setRefreshToken(RefreshTokenJpaEntity refreshToken) { this.refreshToken = refreshToken; }
}
