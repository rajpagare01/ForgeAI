package com.forgeai.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        RsaKeyPropertiesConfig config = new RsaKeyPropertiesConfig();
        ReflectionTestUtils.setField(config, "keyId", "test-key-id");
        var rsaJwk = config.rsaJwk();
        var jwkSource = config.jwkSource(rsaJwk);
        var jwtEncoder = config.jwtEncoder(jwkSource);
        jwtDecoder = config.jwtDecoder(rsaJwk);

        jwtService = new JwtService(jwtEncoder, jwtDecoder);
        ReflectionTestUtils.setField(jwtService, "keyId", "test-key-id");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 3600000L);
        ReflectionTestUtils.setField(jwtService, "issuer", "https://test.forgeai.internal");
    }

    @Test
    void generateAndValidate() {
        UUID id = UUID.randomUUID();
        String token = jwtService.generateToken(id);

        assertNotNull(token);
        assertTrue(jwtService.validateToken(token));
        assertEquals(id, jwtService.extractUserId(token));
        assertEquals(3600L, jwtService.getExpirationSeconds());
    }

    @Test
    void multiTenantClaimsEmbedding() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String role = "OWNER";
        List<String> permissions = List.of("ORGANIZATION_UPDATE", "PROJECT_READ");

        String token = jwtService.generateToken(userId, orgId, role, permissions);

        assertNotNull(token);
        assertTrue(jwtService.validateToken(token));

        Jwt decoded = jwtDecoder.decode(token);
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals(orgId.toString(), decoded.getClaimAsString("org_id"));
        assertEquals(role, decoded.getClaimAsString("role"));
        assertEquals(permissions, decoded.getClaimAsStringList("permissions"));
    }

    @Test
    void invalidTokenFailsValidation() {
        assertFalse(jwtService.validateToken("invalid.token.payload"));
    }
}
