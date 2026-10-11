package com.forgeai.project.domain.exception;

public class InvalidTaskStatusTransitionException extends DomainException {
    public InvalidTaskStatusTransitionException(String message) {
        super(message);
    }
}
