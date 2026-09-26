package com.forgeai.identity.domain.valueobject;

import java.util.regex.Pattern;

/**
 * Email value object.
 *
 * Stores the email address in its original casing (for display purposes).
 * All uniqueness checks and database lookups must use lower(email) — this is
 * enforced by the repository layer and the functional unique index in PostgreSQL.
 *
 * Validation: basic format check (not an exhaustive RFC 5322 parser — that
 * would be over-engineered here; proper validation happens on the network
 * boundary when the email is actually used).
 */
public record Email(String value) {

    private static final Pattern BASIC_EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int MAX_LENGTH = 255;

    public Email {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email must not be null or empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Email must not exceed " + MAX_LENGTH + " characters");
        }
        if (!BASIC_EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Email format is invalid: " + value);
        }
    }

    /**
     * Returns the lowercase form of this email, used for case-insensitive
     * comparison and database lookup (matching the functional unique index).
     */
    public String normalized() {
        return value.toLowerCase();
    }

    @Override
    public String toString() {
        return value;
    }
}
