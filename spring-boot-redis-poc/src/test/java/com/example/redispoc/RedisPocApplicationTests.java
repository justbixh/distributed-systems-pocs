package com.example.redispoc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spring context smoke test.
 * Uses H2 (via src/test/resources/application.yml) and points Redis
 * to localhost:6379 (test environment requirement or skipped in CI).
 */
@SpringBootTest
class RedisPocApplicationTests {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.cache.type",      () -> "none");   // no Redis needed for smoke test
    }

    @Test
    void contextLoads() {
    }
}
