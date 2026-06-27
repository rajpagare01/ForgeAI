package com.forgeai.identity.application.port;

import java.time.Instant;

/**
 * Abstraction over the system clock for testability.
 * Allows use cases to be tested with deterministic time.
 */
public interface ClockPort {

    /** Returns the current instant. */
    Instant now();
}
