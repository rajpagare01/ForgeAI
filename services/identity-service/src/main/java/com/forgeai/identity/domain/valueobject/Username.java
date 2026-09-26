package com.forgeai.identity.domain.valueobject;

import java.util.regex.Pattern;

/**
 * Username value object.
 *
 * Policy:
 * - 3–39 characters
 * - Allowed characters: [a-zA-Z0-9_-]
 * - Must start and end with a letter or digit (not _ or -)
 * - No consecutive special characters (no __ or -- or _- etc.)
 * - Whitespace not permitted
 *
 * Uniqueness: case-insensitive. The repository uses lower(username) for
 * lookups, matching the functional unique index in PostgreSQL.
 * The original casing is preserved for display.
 */
public record Username(String value) {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 39;

    // Allows letters, digits, single underscores/hyphens (not at start/end,
    // not consecutive). Full pattern breakdown:
    //   ^[a-zA-Z0-9]             must start with alphanumeric
    //   ([a-zA-Z0-9_-]*          middle: alphanumeric + single special chars
    //   [a-zA-Z0-9])?$           must end with alphanumeric (unless length 1)
    private static final Pattern VALID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9]|[-_](?=[a-zA-Z0-9]))*$");

    public Username {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Username must be at least " + MIN_LENGTH + " characters");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Username must not exceed " + MAX_LENGTH + " characters");
        }
        if (!VALID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Username '" + value + "' contains invalid characters or pattern. " +
                    "Allowed: letters, digits, single hyphens or underscores (not at start/end).");
        }
    }

    /**
     * Returns the lowercase form used for case-insensitive uniqueness checks
     * and database lookups (matching the functional unique index).
     */
    public String normalized() {
        return value.toLowerCase();
    }

    @Override
    public String toString() {
        return value;
    }
}
