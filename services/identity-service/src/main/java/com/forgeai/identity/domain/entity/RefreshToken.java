package com.forgeai.identity.domain.entity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.forgeai.identity.domain.exception.DomainException;

public class RefreshToken {
    private final UUID tokenId;
    private final String tokenHash;
    private final Instant expiresAt;
    private UUID replacedBy;
    private boolean revoked;

    public RefreshToken(UUID tokenId, String tokenHash, Instant expiresAt) {
        this.tokenId = tokenId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    public UUID getTokenId() { return tokenId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Optional<UUID> getReplacedBy() { return Optional.ofNullable(replacedBy); }
    public boolean isRevoked() { return revoked; }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void revoke() {
        if (this.revoked) throw new DomainException("Refresh token already revoked");
        this.revoked = true;
    }

    public RefreshToken rotate(UUID newTokenId, String newTokenHash, Instant newExpiresAt) {
        if (this.isExpired()) throw new DomainException("Cannot rotate expired refresh token");
        if (this.revoked) throw new DomainException("Cannot rotate revoked refresh token");
        
        this.revoke();
        this.replacedBy = newTokenId;
        
        return new RefreshToken(newTokenId, newTokenHash, newExpiresAt);
    }
}
