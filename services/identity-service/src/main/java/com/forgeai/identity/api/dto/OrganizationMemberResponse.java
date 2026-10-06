package com.forgeai.identity.api.dto;

import java.util.UUID;

public record OrganizationMemberResponse(
        UUID membershipId,
        UUID userId,
        String username,
        String email,
        String role,
        String status
) {
}
