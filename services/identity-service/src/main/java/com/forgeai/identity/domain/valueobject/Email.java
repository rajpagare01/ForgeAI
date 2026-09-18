package com.forgeai.identity.domain.valueobject;

public record Email(String value) {
    public Email {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or empty");
        }
        // Basic format validation can be added here later
    }
}
