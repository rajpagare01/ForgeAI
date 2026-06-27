package com.forgeai.identity.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Output port for caching data (e.g., session blacklists, user projections).
 * Implemented in the infrastructure layer using Redis.
 */
public interface CachePort {

    /**
     * Stores a value in the cache with a TTL.
     *
     * @param key   the cache key
     * @param value the value to store
     * @param ttl   the time-to-live
     */
    void put(String key, String value, Duration ttl);

    /**
     * Retrieves a value from the cache.
     *
     * @param key the cache key
     * @return the value, or empty if absent or expired
     */
    Optional<String> get(String key);

    /**
     * Evicts a key from the cache.
     *
     * @param key the cache key to evict
     */
    void evict(String key);
}
