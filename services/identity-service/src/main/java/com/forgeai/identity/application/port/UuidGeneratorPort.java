package com.forgeai.identity.application.port;

import java.util.UUID;

/**
 * Abstraction over UUID generation for testability.
 */
public interface UuidGeneratorPort {

    /** Generates a new random UUID. */
    UUID generate();
}
