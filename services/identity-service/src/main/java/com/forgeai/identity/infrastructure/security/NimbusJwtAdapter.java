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
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
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
            @Value("${jwt.private-key:classpath:keys/private.pem}") Resource privateKeyResource,
            @Value("${jwt.public-key:classpath:keys/public.pem}") Resource publicKeyResource,
            @Value("${jwt.issuer:forgeai-identity}") String issuer,
            @Value("${jwt.ttl-seconds:900}") long ttlSeconds) throws Exception {
        
        KeyFactory kf = KeyFactory.getInstance("RSA");
        
        String privKeyStr = new String(privateKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        privKeyStr = privKeyStr.replace("-----BEGIN PRIVATE KEY-----", "")
                               .replace("-----END PRIVATE KEY-----", "")
                               .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                               .replace("-----END RSA PRIVATE KEY-----", "")
                               .replaceAll("\\s+", "");
        this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privKeyStr)));
        
        String pubKeyStr = new String(publicKeyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        pubKeyStr = pubKeyStr.replace("-----BEGIN PUBLIC KEY-----", "")
                             .replace("-----END PUBLIC KEY-----", "")
                             .replaceAll("\\s+", "");
        this.publicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubKeyStr)));
        
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

            if (claims != null) {
                claims.forEach(builder::claim);
            }

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
            RSASSAVerifier verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
            
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
