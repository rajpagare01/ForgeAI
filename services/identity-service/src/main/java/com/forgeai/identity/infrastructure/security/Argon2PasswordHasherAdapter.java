package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.application.port.PasswordHasherPort;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2PasswordHasherAdapter implements PasswordHasherPort {

    private final Argon2PasswordEncoder encoder;

    public Argon2PasswordHasherAdapter() {
        // Defaults: 16 byte salt, 32 byte hash, 1 iter, 4096 memory, 1 parallel
        this.encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean verify(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }
}
