package com.forgeai.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    
    private final String secret = "12345678901234567890123456789012";
    private final JwtService jwtService = new JwtService(secret, 3600000); // 1 hour
    
    @Test
    void generateAndValidate() {
        UUID id = UUID.randomUUID();
        String token = jwtService.generateToken(id);
        
        assertNotNull(token);
        assertTrue(jwtService.validateToken(token));
        assertEquals(id, jwtService.extractUserId(token));
    }
    
    @Test
    void expiredToken() throws Exception {
        JwtService shortJwt = new JwtService(secret, 1); // 1 ms expiration
        String token = shortJwt.generateToken(UUID.randomUUID());
        
        Thread.sleep(10);
        
        assertFalse(shortJwt.validateToken(token));
    }
    
    @Test
    void invalidSignature() {
        JwtService otherJwt = new JwtService("abcdefghijklmnopqrstuvwxyz123456", 3600000);
        String token = jwtService.generateToken(UUID.randomUUID());
        
        assertFalse(otherJwt.validateToken(token));
    }
}
