package com.forgeai.identity.domain.entity;

import java.time.Instant;
import com.forgeai.identity.domain.exception.DomainException;

public class PasswordResetToken {
    private final String token;
    private final Instant expiration;
    private boolean used;

    public PasswordResetToken(String token, Instant expiration) {
        this.token = token;
        this.expiration = expiration;
        this.used = false;
    }

    public String getToken() { return token; }
    public Instant getExpiration() { return expiration; }
    public boolean isUsed() { return used; }

    public boolean isExpired() {
        return Instant.now().isAfter(expiration);
    }

    public void consume() {
        if (this.used) throw new DomainException("Password reset token already used");
        if (this.isExpired()) throw new DomainException("Password reset token is expired");
        this.used = true;
    }
}
