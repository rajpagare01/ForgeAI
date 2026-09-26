package com.forgeai.identity.domain.exception;

public class PermissionNotFoundException extends ResourceNotFoundException {
    public PermissionNotFoundException(String message) {
        super(message);
    }
}

