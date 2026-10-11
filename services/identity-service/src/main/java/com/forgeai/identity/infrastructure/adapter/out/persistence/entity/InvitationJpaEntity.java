package com.forgeai.identity.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
@Getter
@Setter
public class InvitationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
