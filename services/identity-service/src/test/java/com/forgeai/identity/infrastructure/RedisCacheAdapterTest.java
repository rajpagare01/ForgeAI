package com.forgeai.identity.infrastructure;

import com.forgeai.identity.infrastructure.cache.RedisCacheAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class RedisCacheAdapterTest extends BaseIntegrationTest {

    @Autowired
    private RedisCacheAdapter redisCacheAdapter;

    @Test
    void testPutAndGet() {
        redisCacheAdapter.put("test_key", "test_value", Duration.ofMinutes(1));
        assertThat(redisCacheAdapter.get("test_key")).contains("test_value");

        redisCacheAdapter.evict("test_key");
        assertThat(redisCacheAdapter.get("test_key")).isEmpty();
    }
}
