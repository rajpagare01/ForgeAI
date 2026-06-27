package com.forgeai.identity.infrastructure.email;

import com.forgeai.identity.application.port.EmailSenderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConsoleEmailAdapter implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailAdapter.class);

    @Override
    public void sendVerificationEmail(String recipientEmail, String token) {
        log.info("📧 Sending Verification Email to {}: Token={}", recipientEmail, token);
    }

    @Override
    public void sendPasswordResetEmail(String recipientEmail, String token) {
        log.info("📧 Sending Password Reset Email to {}: Token={}", recipientEmail, token);
    }
}
