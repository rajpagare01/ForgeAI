package com.forgeai.identity.domain.model;

import com.forgeai.identity.domain.valueobject.Email;
import com.forgeai.identity.domain.valueobject.Username;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class User {
    private UUID id;
    private Username username;
    private Email email;
    private String passwordHash; // Placeholder
    private boolean active;
}
