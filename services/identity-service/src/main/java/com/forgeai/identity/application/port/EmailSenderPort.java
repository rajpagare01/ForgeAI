package com.forgeai.identity.application.port;

/**
 * Output port for sending transactional emails.
 * Implemented in the infrastructure layer via SMTP or a managed email service.
 */
public interface EmailSenderPort {

    /**
     * Sends an email verification link.
     *
     * @param recipientEmail the user's email
     * @param token          the verification token
     */
    void sendVerificationEmail(String recipientEmail, String token);

    /**
     * Sends a password-reset link.
     *
     * @param recipientEmail the user's email
     * @param token          the reset token
     */
    void sendPasswordResetEmail(String recipientEmail, String token);
}
