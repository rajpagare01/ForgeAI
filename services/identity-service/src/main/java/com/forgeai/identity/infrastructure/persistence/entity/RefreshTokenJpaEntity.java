package com.forgeai.identity.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity mapping for the RefreshToken domain entity.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(nullable = false)
    private boolean revoked;

    @Version
    private Long version;

    protected RefreshTokenJpaEntity() {
        // JPA constructor
    }

    public RefreshTokenJpaEntity(UUID id, UUID sessionId, String tokenHash, Instant expiresAt, UUID replacedBy, boolean revoked) {
        this.id = id;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.replacedBy = replacedBy;
        this.revoked = revoked;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getReplacedBy() { return replacedBy; }
    public boolean isRevoked() { return revoked; }
    public Long getVersion() { return version; }
}
