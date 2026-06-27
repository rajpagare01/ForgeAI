package com.forgeai.identity.application.port;

/**
 * Output port for hashing and verifying passwords.
 * Implemented in the infrastructure layer using Argon2 or BCrypt.
 */
public interface PasswordHasherPort {

    /**
     * Hashes a raw password using a secure, one-way algorithm.
     *
     * @param rawPassword the plaintext password
     * @return the hashed password string
     */
    String hash(String rawPassword);

    /**
     * Verifies that a raw password matches a stored hash.
     *
     * @param rawPassword the plaintext password to check
     * @param storedHash  the previously stored hash
     * @return {@code true} if the password matches
     */
    boolean verify(String rawPassword, String storedHash);
}
