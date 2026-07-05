package com.forgeai.identity.presentation.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class ValidDeviceIdValidator implements ConstraintValidator<ValidDeviceId, String> {

    // Simple alphanumeric plus dashes and underscores
    private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9\\-_]+$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        return value.length() >= 5 && value.length() <= 255 && PATTERN.matcher(value).matches();
    }
}
