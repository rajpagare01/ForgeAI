package com.forgeai.identity.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_states")
@Getter
@Setter
public class OAuthStateJpaEntity {

    @Id
    @Column(name = "state", length = 128)
    private String state;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "redirect_url", length = 1024)
    private String redirectUrl;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
