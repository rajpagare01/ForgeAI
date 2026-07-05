package com.forgeai.identity.infrastructure.email;

import com.forgeai.identity.application.port.EmailSenderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpEmailAdapter implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailAdapter.class);
    private final JavaMailSender mailSender;

    public SmtpEmailAdapter(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendVerificationEmail(String recipientEmail, String token) {
        log.info("Sending Verification Email to {}", recipientEmail);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipientEmail);
        message.setSubject("Verify your account");
        message.setText("Your verification token is: " + token);
        mailSender.send(message);
    }

    @Override
    public void sendPasswordResetEmail(String recipientEmail, String token) {
        log.info("Sending Password Reset Email to {}", recipientEmail);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipientEmail);
        message.setSubject("Reset your password");
        message.setText("Your password reset token is: " + token);
        mailSender.send(message);
    }
}
