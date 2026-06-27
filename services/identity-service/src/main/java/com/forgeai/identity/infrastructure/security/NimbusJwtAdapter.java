package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.application.port.JwtGeneratorPort;
import com.forgeai.identity.application.port.TokenVerifierPort;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class NimbusJwtAdapter implements JwtGeneratorPort, TokenVerifierPort {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String issuer;
    private final long ttlSeconds;

    public NimbusJwtAdapter(
            @Value("${jwt.private-key-base64:MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEAxZ61/1vP4M4154Tf1tO0Rj8c34i8K1j113q1oR6c70E+n4zD74k5F4l5A1u/6yR4F0R1h4L7Q8P+0e5Q7E2J/QIDAQABAkBP0/X8Q1O2E3L9V7B1lX1B6V8Y7c4S0Q7Z1P8H6P1U2M5L8Z9O2F4V7N5K6C7T8P2R9Y1U4Q7F6Z4W1X9A3O4BAiEA5Z2/Q4B2E6A1Q5V2X8L7E1W5N8L3U9Y7E4O1B2P8E8MCIQDp6M3X2Q1O4Y6P7A2V5E4W7L1U8Y6R3O2T4B1E9M6L5QIgO3L8Q1A7B4P6Z5R2T1Y7E6O2U8M1W4Q7E9V6M3X5P4QIgE9V6M3X5P4QIgO3L8Q1A7B4P6Z5R2T1Y7E6O2U8M1W4Q7E9V6M3X5P4QIgO3L8Q1A7B4P6Z5R2T1Y7E6O2U8M1W4Q7E9V6M3X5P4Q}") String privateKeyBase64,
            @Value("${jwt.public-key-base64:MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAMWetf9bz+DONeeE39bTtEY/HN+IvCtY9dd6taEenO9BPp+Mw++JOReJeQNbv+skeBdEdYeC+0PD/tHuUOxNif0CAwEAAQ==}") String publicKeyBase64,
            @Value("${jwt.issuer:forgeai-identity}}") String issuer,
            @Value("${jwt.ttl-seconds:900}}") long ttlSeconds) throws Exception {
        
        KeyFactory kf = KeyFactory.getInstance("RSA");
        
        // Very basic hardcoded fallback keys for the local environment setup,
        // in production these are injected via Vault.
        this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
        this.publicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public String generateAccessToken(UUID userId, UUID sessionId, Map<String, Object> claims) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .claim("sid", sessionId.toString())
                    .issuer(issuer)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .jwtID(UUID.randomUUID().toString());

            claims.forEach(builder::claim);

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                    builder.build()
            );

            signedJWT.sign(new RSASSASigner(privateKey));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    @Override
    public long getAccessTokenTtlSeconds() {
        return ttlSeconds;
    }

    @Override
    public Optional<Map<String, Object>> verify(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            RSASSAVerifier verifier = new RSASSAVerifier((java.security.interfaces.RSAPublicKey) publicKey);
            
            if (signedJWT.verify(verifier)) {
                JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();
                if (claimsSet.getExpirationTime().after(new Date())) {
                    return Optional.of(claimsSet.getClaims());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
