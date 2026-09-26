package com.forgeai.identity.domain.exception;

public class MembershipNotFoundException extends ResourceNotFoundException {
    public MembershipNotFoundException(String message) {
        super(message);
    }
}

