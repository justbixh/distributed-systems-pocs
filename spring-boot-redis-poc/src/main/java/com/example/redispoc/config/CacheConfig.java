package com.example.redispoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Cache configuration defining default caching behavior and
 * per-cache TTL policies.
 */
@Configuration
public class CacheConfig {

    public static final String PRODUCTS_CACHE    = "products";
    public static final String PRODUCT_CACHE     = "product";
    public static final String PRODUCT_SEARCH_CACHE = "productSearch";

    @Bean
    @Primary
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        var jsonSerializer = RedisSerializer.json();

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues()
                .prefixCacheNameWith("redis-poc:");

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                PRODUCTS_CACHE,       defaultConfig.entryTtl(Duration.ofMinutes(5)),
                PRODUCT_CACHE,        defaultConfig.entryTtl(Duration.ofMinutes(15)),
                PRODUCT_SEARCH_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(2))
        );

        // Wrap the default RedisCacheWriter with LoggingRedisCacheWriter for real-time latency logging
        RedisCacheWriter cacheWriter = new LoggingRedisCacheWriter(
                RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory));

        return RedisCacheManager.builder(cacheWriter)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }
}
