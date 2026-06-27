package com.forgeai.identity.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity mapping for the Session domain aggregate.
 */
@Entity
@Table(name = "sessions")
public class SessionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_accessed", nullable = false)
    private Instant lastAccessed;

    @Version
    private Long version;

    protected SessionJpaEntity() {
        // JPA constructor
    }

    public SessionJpaEntity(UUID id, UUID userId, String deviceInfo, String ipAddress, String userAgent, String status, Instant createdAt, Instant lastAccessed) {
        this.id = id;
        this.userId = userId;
        this.deviceInfo = deviceInfo;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.status = status;
        this.createdAt = createdAt;
        this.lastAccessed = lastAccessed;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getDeviceInfo() { return deviceInfo; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastAccessed() { return lastAccessed; }
    public Long getVersion() { return version; }
}
