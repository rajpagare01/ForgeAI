package com.forgeai.identity.presentation.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidUserAgentValidator implements ConstraintValidator<ValidUserAgent, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        // Basic check for safe characters (prevent injection)
        return value.length() <= 512 && value.matches("^[a-zA-Z0-9 \\-._/()]+$");
    }
}
