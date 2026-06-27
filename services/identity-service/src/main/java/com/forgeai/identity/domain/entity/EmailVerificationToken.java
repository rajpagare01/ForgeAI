package com.forgeai.identity.domain.entity;

import java.time.Instant;
import com.forgeai.identity.domain.exception.DomainException;

public class EmailVerificationToken {
    private final String token;
    private final Instant expiration;
    private boolean used;

    public EmailVerificationToken(String token, Instant expiration) {
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

    public void verify() {
        if (this.used) throw new DomainException("Email verification token already used");
        if (this.isExpired()) throw new DomainException("Email verification token is expired");
        this.used = true;
    }
}
