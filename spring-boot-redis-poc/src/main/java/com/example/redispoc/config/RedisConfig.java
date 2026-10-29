package com.example.redispoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration using Spring Data Redis 4.0 APIs.
 * Redis is used exclusively as a cache and atomic counter store.
 * MySQL (via JPA) is the system of record for all entities.
 *
 * Serialization:
 *  - Keys   → plain String (StringRedisSerializer)
 *  - Values → JSON via RedisSerializer.json() (GenericJacksonJsonRedisSerializer, Jackson 3.x)
 */
@Configuration
public class RedisConfig {

    /**
     * Primary RedisTemplate with String keys and JSON values.
     * Used for atomic counter operations (view counts, rate-limiting, etc.).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connectionFactory);

        var stringSerializer = new StringRedisSerializer();
        var jsonSerializer   = RedisSerializer.json();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setDefaultSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }

    /**
     * StringRedisTemplate for simple string key-value operations.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
