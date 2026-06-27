package com.forgeai.identity.domain.service;

import com.forgeai.identity.domain.exception.PasswordPolicyException;

public interface PasswordPolicyService {
    /**
     * Validates if a raw password meets the domain complexity requirements.
     * Rules: minimum 8 chars, 1 uppercase, 1 lowercase, 1 digit, 1 special char.
     * @param rawPassword the unhashed password string
     * @throws PasswordPolicyException if requirements are not met
     */
    void validate(String rawPassword) throws PasswordPolicyException;
}
