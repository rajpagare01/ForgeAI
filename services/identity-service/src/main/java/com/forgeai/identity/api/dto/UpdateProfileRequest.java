package com.forgeai.identity.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Mutable profile update request.
 * Only exposes intentionally user-editable fields.
 * id, status, passwordHash, emailVerified, createdAt, updatedAt cannot be set here.
 */
public record UpdateProfileRequest(
        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName
) {}
