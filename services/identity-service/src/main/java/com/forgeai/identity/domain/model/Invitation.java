package com.forgeai.identity.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invitation {
    private UUID id;
    private UUID organizationId;
    private String email;
    private UUID roleId;
    private UUID invitedBy;
    private String token;
    private InvitationStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
