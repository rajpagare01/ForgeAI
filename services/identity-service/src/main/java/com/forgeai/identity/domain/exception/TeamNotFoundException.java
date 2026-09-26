package com.forgeai.identity.domain.exception;

public class TeamNotFoundException extends ResourceNotFoundException {
    public TeamNotFoundException(String message) {
        super(message);
    }
}

