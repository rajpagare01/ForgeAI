package com.forgeai.identity.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@Configuration
public class RsaKeyPropertiesConfig {

    @Value("${security.jwt.rsa.key-id:forgeai-identity-key-v1}")
    private String keyId;

    @Value("${security.jwt.rsa.public-key:}")
    private String publicKeyPem;

    @Value("${security.jwt.rsa.private-key:}")
    private String privateKeyPem;

    @Bean
    public RSAKey rsaJwk() {
        try {
            RSAPublicKey publicKey;
            RSAPrivateKey privateKey;

            if (publicKeyPem != null && !publicKeyPem.isBlank() && privateKeyPem != null && !privateKeyPem.isBlank()) {
                publicKey = parsePublicKey(publicKeyPem);
                privateKey = parsePrivateKey(privateKeyPem);
                log.info("Initialized RSA Key Pair from external configuration. Key ID: {}", keyId);
            } else {
                log.warn("External RSA keys not provided in configuration. Generating an ephemeral in-memory 2048-bit RSA key pair for testing/local environment.");
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
                keyPairGenerator.initialize(2048);
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                publicKey = (RSAPublicKey) keyPair.getPublic();
                privateKey = (RSAPrivateKey) keyPair.getPrivate();
            }

            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(keyId)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure RSA JWK signing keys", e);
        }
    }

    @Bean
    public JWKSet publicJwkSet(RSAKey rsaJwk) {
        // Expose ONLY the public key components
        return new JWKSet(rsaJwk.toPublicJWK());
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey rsaJwk) {
        JWKSet jwkSet = new JWKSet(rsaJwk);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaJwk) throws Exception {
        return NimbusJwtDecoder.withPublicKey(rsaJwk.toRSAPublicKey()).build();
    }

    private RSAPublicKey parsePublicKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(clean);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    private RSAPrivateKey parsePrivateKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(clean);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }
}
