package com.forgeai.identity.infrastructure.cache;

import com.forgeai.identity.application.port.CachePort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Adapter for Redis cache.
 * Note: Temporarily using in-memory map to allow compilation without Spring Data Redis dependency,
 * but fully conforms to the interface expected by the application.
 */
@Component
public class RedisCacheAdapter implements CachePort {

    private final Map<String, String> mockRedis = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        mockRedis.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(mockRedis.get(key));
    }

    @Override
    public void evict(String key) {
        mockRedis.remove(key);
    }
}
