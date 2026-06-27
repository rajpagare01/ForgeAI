package com.forgeai.identity.infrastructure.configuration;

import com.forgeai.identity.domain.service.PasswordPolicyService;
import com.forgeai.identity.domain.service.TokenGenerationService;
import com.forgeai.identity.domain.exception.PasswordPolicyException;
import com.forgeai.identity.domain.entity.EmailVerificationToken;
import com.forgeai.identity.domain.entity.PasswordResetToken;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Spring Configuration to instantiate Domain Services as Spring Beans.
 * Keeps Domain Layer free of @Service / @Component annotations.
 */
@Configuration
public class DomainConfig {

    @Bean
    public PasswordPolicyService passwordPolicyService() {
        return new PasswordPolicyService() {
            private final Pattern UPPER = Pattern.compile("[A-Z]");
            private final Pattern LOWER = Pattern.compile("[a-z]");
            private final Pattern DIGIT = Pattern.compile("[0-9]");
            private final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]");

            @Override
            public void validate(String rawPassword) throws PasswordPolicyException {
                if (rawPassword == null || rawPassword.length() < 8) {
                    throw new PasswordPolicyException("Password must be at least 8 characters long.");
                }
                if (!UPPER.matcher(rawPassword).find()) {
                    throw new PasswordPolicyException("Password must contain at least one uppercase letter.");
                }
                if (!LOWER.matcher(rawPassword).find()) {
                    throw new PasswordPolicyException("Password must contain at least one lowercase letter.");
                }
                if (!DIGIT.matcher(rawPassword).find()) {
                    throw new PasswordPolicyException("Password must contain at least one digit.");
                }
                if (!SPECIAL.matcher(rawPassword).find()) {
                    throw new PasswordPolicyException("Password must contain at least one special character.");
                }
            }
        };
    }

    @Bean
    public TokenGenerationService tokenGenerationService() {
        return new TokenGenerationService() {
            private final SecureRandom secureRandom = new SecureRandom();

            private String generateSecureString() {
                byte[] bytes = new byte[32];
                secureRandom.nextBytes(bytes);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }

            @Override
            public EmailVerificationToken generateEmailVerificationToken() {
                Instant expiration = Instant.now().plus(24, ChronoUnit.HOURS);
                return new EmailVerificationToken(generateSecureString(), expiration);
            }

            @Override
            public PasswordResetToken generatePasswordResetToken() {
                Instant expiration = Instant.now().plus(1, ChronoUnit.HOURS);
                return new PasswordResetToken(generateSecureString(), expiration);
            }
        };
    }
}
