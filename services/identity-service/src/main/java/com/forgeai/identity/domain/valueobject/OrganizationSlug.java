package com.forgeai.identity.domain.valueobject;

import java.util.regex.Pattern;

/**
 * OrganizationSlug value object.
 *
 * Policy:
 * - 2–63 characters
 * - Lowercase letters, digits, and hyphens only: [a-z0-9-]
 * - Must start and end with a letter or digit
 * - No consecutive hyphens
 * - Used as the URL-safe organization identifier (e.g., /orgs/my-company)
 */
public record OrganizationSlug(String value) {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 63;

    private static final Pattern VALID_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9]|-(?=[a-z0-9]))*$");

    public OrganizationSlug {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Organization slug must not be null or empty");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Organization slug must be at least " + MIN_LENGTH + " characters");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Organization slug must not exceed " + MAX_LENGTH + " characters");
        }
        if (!VALID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Organization slug '" + value + "' is invalid. " +
                    "Allowed: lowercase letters, digits, and single hyphens (not at start/end).");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
