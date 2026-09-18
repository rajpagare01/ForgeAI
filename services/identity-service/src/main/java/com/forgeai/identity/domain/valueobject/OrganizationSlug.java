package com.forgeai.identity.domain.valueobject;

public record OrganizationSlug(String value) {
    public OrganizationSlug {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OrganizationSlug cannot be null or empty");
        }
    }
}
