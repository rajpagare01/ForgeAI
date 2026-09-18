package com.forgeai.identity.domain.valueobject;

public record Username(String value) {
    public Username {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
    }
}
