package com.forgeai.sourcecontrol.infrastructure.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubRateLimitTrackerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private GitHubRateLimitTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new GitHubRateLimitTracker(redisTemplate);
    }

    @Test
    @DisplayName("When capacity is healthy (>10%), both high-priority and background calls proceed")
    void healthyCapacityAllowsAllCalls() {
        Long installationId = 123456L;
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("github:ratelimit:123456", "remaining")).thenReturn("4500");
        when(hashOperations.get("github:ratelimit:123456", "limit")).thenReturn("5000");

        boolean allowHighPriority = tracker.acquireApiPermit(installationId, true);
        boolean allowBackground = tracker.acquireApiPermit(installationId, false);

        assertThat(allowHighPriority).isTrue();
        assertThat(allowBackground).isTrue();
    }

    @Test
    @DisplayName("When capacity drops below 10%, background tasks are throttled while live webhooks proceed")
    void lowCapacityThrottlesBackgroundOnly() {
        Long installationId = 123456L;
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        // 400 out of 5000 is 8% (< 10%)
        when(hashOperations.get("github:ratelimit:123456", "remaining")).thenReturn("400");
        when(hashOperations.get("github:ratelimit:123456", "limit")).thenReturn("5000");

        // High-priority live webhook response MUST proceed
        boolean allowHighPriority = tracker.acquireApiPermit(installationId, true);
        assertThat(allowHighPriority).isTrue();

        // Background synchronization job MUST be throttled
        boolean allowBackground = tracker.acquireApiPermit(installationId, false);
        assertThat(allowBackground).isFalse();
    }

    @Test
    @DisplayName("Fail-safe: When Redis is unreachable, in-memory fallback protects API budget")
    void redisFailureFallsBackToMemory() {
        Long installationId = 789101L;
        // Seed in-memory cache directly with low capacity (200 out of 5000 = 4%)
        tracker.updateRateLimit(installationId, 5000, 200, Instant.now().getEpochSecond() + 1800);

        when(redisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis connection refused"));

        // Live webhook still succeeds
        boolean allowLive = tracker.acquireApiPermit(installationId, true);
        assertThat(allowLive).isTrue();

        // Background task throttled via local memory
        boolean allowBackground = tracker.acquireApiPermit(installationId, false);
        assertThat(allowBackground).isFalse();
    }
}
