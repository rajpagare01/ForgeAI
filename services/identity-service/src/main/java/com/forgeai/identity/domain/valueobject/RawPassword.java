package com.forgeai.identity.domain.valueobject;

import java.util.regex.Pattern;

/**
 * RawPassword value object.
 *
 * Enforces strong password policy.
 */
public record RawPassword(String value) {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;
    private static final Pattern POLICY_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&_\\-]).+$");

    public RawPassword {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Password must not be null or empty");
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!POLICY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter, one lowercase letter, one digit, and one special character");
        }
    }
}
