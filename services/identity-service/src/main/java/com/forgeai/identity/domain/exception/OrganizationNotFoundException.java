package com.forgeai.identity.domain.exception;

public class OrganizationNotFoundException extends ResourceNotFoundException {
    public OrganizationNotFoundException(String message) {
        super(message);
    }
}

