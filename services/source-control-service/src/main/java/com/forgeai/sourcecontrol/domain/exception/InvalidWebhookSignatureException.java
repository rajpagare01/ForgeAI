package com.forgeai.sourcecontrol.domain.exception;

public class InvalidWebhookSignatureException extends DomainException {
    public InvalidWebhookSignatureException(String message) {
        super(message);
    }
}
