package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.application.port.out.TokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService implements TokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    @Value("${security.jwt.rsa.key-id:forgeai-identity-key-v1}")
    private String keyId;

    @Value("${security.jwt.expiration-ms:900000}")
    private long expirationMs;

    @Value("${security.jwt.issuer:https://forgeai.internal/identity}")
    private String issuer;

    @Override
    public String generateToken(UUID userId) {
        return generateToken(userId, null, null, Collections.emptyList());
    }

    @Override
    public String generateToken(UUID userId, UUID orgId, String role, List<String> permissions) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(expirationMs);

        JwsHeader jwsHeader = JwsHeader.with(() -> "RS256")
                .keyId(keyId)
                .build();

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString());

        if (orgId != null) {
            claimsBuilder.claim("org_id", orgId.toString());
        }

        if (role != null && !role.isBlank()) {
            claimsBuilder.claim("role", role);
        }

        if (permissions != null && !permissions.isEmpty()) {
            claimsBuilder.claim("permissions", permissions);
        }

        JwtClaimsSet claims = claimsBuilder.build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    @Override
    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }

    public boolean validateToken(String token) {
        try {
            jwtDecoder.decode(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public UUID extractUserId(String token) {
        var jwt = jwtDecoder.decode(token);
        return UUID.fromString(jwt.getSubject());
    }
}
