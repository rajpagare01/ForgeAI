package com.forgeai.identity.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationTokenJpaEntity extends BaseJpaEntity {

    @Id
    @Column(name = "token", updatable = false, nullable = false)
    private String token;

    @Column(name = "expiration", nullable = false)
    private Instant expiration;

    @Column(name = "used", nullable = false)
    private boolean used;

    protected EmailVerificationTokenJpaEntity() {
        // JPA constructor
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
}
