package com.forgeai.identity.infrastructure.adapter.out.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(new BCryptPasswordEncoder());

    @Test
    void hashesAreDifferentAndMatch() {
        String raw = "MyStrongPass1!";
        
        String hash1 = hasher.hash(raw);
        String hash2 = hasher.hash(raw);
        
        assertNotEquals(raw, hash1);
        assertNotEquals(hash1, hash2); // BCrypt generates different salts
        
        assertTrue(hasher.matches(raw, hash1));
        assertTrue(hasher.matches(raw, hash2));
        assertFalse(hasher.matches("wrongpassword", hash1));
    }
}
