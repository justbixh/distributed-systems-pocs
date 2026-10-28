package com.example.redispoc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache configuration with per-cache TTL overrides.
 * Defines named caches with different expiration policies.
 */
@Configuration
public class CacheConfig {

    public static final String PRODUCTS_CACHE = "products";
    public static final String PRODUCT_CACHE = "product";
    public static final String PRODUCT_SEARCH_CACHE = "productSearch";

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                      @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper) {
        var jsonSerializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer))
                .disableCachingNullValues()
                .prefixCacheNameWith("redis-poc:");

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put(PRODUCTS_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put(PRODUCT_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put(PRODUCT_SEARCH_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(2)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()
                .build();
    }
}
