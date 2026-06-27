package com.forgeai.identity.domain.service;

import com.forgeai.identity.domain.entity.EmailVerificationToken;
import com.forgeai.identity.domain.entity.PasswordResetToken;

public interface TokenGenerationService {
    /**
     * Generates a cryptographically secure token for email verification.
     */
    EmailVerificationToken generateEmailVerificationToken();

    /**
     * Generates a cryptographically secure token for password resets.
     */
    PasswordResetToken generatePasswordResetToken();
}
