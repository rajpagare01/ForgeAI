package com.forgeai.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(
        UUID id,
        UUID organizationId,
        String email,
        UUID roleId,
        UUID invitedBy,
        String status,
        Instant expiresAt,
        Instant createdAt,
        String token
) {}
