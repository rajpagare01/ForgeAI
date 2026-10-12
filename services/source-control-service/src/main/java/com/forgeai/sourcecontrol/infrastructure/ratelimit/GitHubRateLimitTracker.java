package com.forgeai.sourcecontrol.infrastructure.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class GitHubRateLimitTracker {

    private static final String REDIS_KEY_PREFIX = "github:ratelimit:";
    private static final double CAPACITY_THRESHOLD_RATIO = 0.10; // 10% threshold

    private final StringRedisTemplate redisTemplate;
    
    // In-memory fallback if Redis is unavailable (Fail-safe semantics)
    private final ConcurrentHashMap<Long, Integer> localRemainingMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> localLimitMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> localResetMap = new ConcurrentHashMap<>();

    public GitHubRateLimitTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Updates rate limit capacity metrics reported by GitHub response headers:
     * - X-RateLimit-Limit
     * - X-RateLimit-Remaining
     * - X-RateLimit-Reset (epoch seconds)
     */
    public void updateRateLimit(Long installationId, int limit, int remaining, long resetEpochSeconds) {
        if (installationId == null) {
            return;
        }

        // Always update in-memory fallback
        localLimitMap.put(installationId, limit);
        localRemainingMap.put(installationId, remaining);
        localResetMap.put(installationId, Instant.ofEpochSecond(resetEpochSeconds));

        // Update Redis hash
        try {
            String key = REDIS_KEY_PREFIX + installationId;
            redisTemplate.opsForHash().put(key, "limit", String.valueOf(limit));
            redisTemplate.opsForHash().put(key, "remaining", String.valueOf(remaining));
            redisTemplate.opsForHash().put(key, "reset", String.valueOf(resetEpochSeconds));
            
            long ttlSeconds = Math.max(60, resetEpochSeconds - Instant.now().getEpochSecond());
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
            
            log.debug("Updated GitHub rate limit for installation {}: remaining={}/{}, reset={}",
                    installationId, remaining, limit, resetEpochSeconds);
        } catch (Exception e) {
            log.warn("Failed to write GitHub rate limit to Redis (using local in-memory state): {}", e.getMessage());
        }
    }

    /**
     * Checks if remaining GitHub API budget allows acquiring a permit.
     * Throttles low-priority background sync if remaining capacity is below 10%.
     * Always allows live webhook responses (high priority).
     *
     * @param installationId GitHub App installation ID
     * @param isHighPriority true for live webhook ingest responses, false for background sync
     * @return true if permit acquired, false if throttled
     */
    public boolean acquireApiPermit(Long installationId, boolean isHighPriority) {
        if (installationId == null) {
            return true;
        }

        // Live webhooks always proceed
        if (isHighPriority) {
            return true;
        }

        int limit = 5000;
        int remaining = 5000;
        long resetEpochSeconds = Instant.now().getEpochSecond() + 3600;

        try {
            String key = REDIS_KEY_PREFIX + installationId;
            Object remVal = redisTemplate.opsForHash().get(key, "remaining");
            Object limVal = redisTemplate.opsForHash().get(key, "limit");
            Object resetVal = redisTemplate.opsForHash().get(key, "reset");

            if (remVal != null && limVal != null) {
                remaining = Integer.parseInt((String) remVal);
                limit = Integer.parseInt((String) limVal);
            } else {
                remaining = localRemainingMap.getOrDefault(installationId, 5000);
                limit = localLimitMap.getOrDefault(installationId, 5000);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable, using local memory for permit check: {}", e.getMessage());
            remaining = localRemainingMap.getOrDefault(installationId, 5000);
            limit = localLimitMap.getOrDefault(installationId, 5000);
        }

        double remainingRatio = (double) remaining / (double) Math.max(1, limit);
        if (remainingRatio < CAPACITY_THRESHOLD_RATIO) {
            log.warn("Throttling background API call for installation {}: capacity {}% (< 10% threshold)",
                    installationId, String.format("%.2f", remainingRatio * 100));
            return false;
        }

        return true;
    }

    public int getRemainingCapacity(Long installationId) {
        try {
            String key = REDIS_KEY_PREFIX + installationId;
            Object remVal = redisTemplate.opsForHash().get(key, "remaining");
            if (remVal != null) {
                return Integer.parseInt((String) remVal);
            }
        } catch (Exception ignored) {
        }
        return localRemainingMap.getOrDefault(installationId, 5000);
    }
}
