package com.example.redispoc.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Decorator for {@link RedisCacheWriter} that measures and logs latency for every
 * Redis cache interaction (get, put, evict, clear).
 */
@Slf4j
@RequiredArgsConstructor
public class LoggingRedisCacheWriter implements RedisCacheWriter {

    private final RedisCacheWriter delegate;

    @Override
    public byte @Nullable [] get(String name, byte[] key) {
        long start = System.nanoTime();
        byte[] result = delegate.get(name, key);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        String keyStr = new String(key);
        if (result != null) {
            log.info("[REDIS] Cache HIT  | cache='{}' key='{}' | latency={}", name, keyStr, formatLatency(latencyMs));
        } else {
            log.info("[REDIS] Cache MISS | cache='{}' key='{}' | latency={}", name, keyStr, formatLatency(latencyMs));
        }
        return result;
    }

    @Override
    public byte @Nullable [] get(String name, byte[] key, @Nullable Duration ttl) {
        long start = System.nanoTime();
        byte[] result = delegate.get(name, key, ttl);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        String keyStr = new String(key);
        if (result != null) {
            log.info("[REDIS] Cache HIT  | cache='{}' key='{}' | latency={}", name, keyStr, formatLatency(latencyMs));
        } else {
            log.info("[REDIS] Cache MISS | cache='{}' key='{}' | latency={}", name, keyStr, formatLatency(latencyMs));
        }
        return result;
    }

    @Override
    public byte[] get(String name, byte[] key, Supplier<byte[]> valueLoader, @Nullable Duration ttl, boolean timeToIdleEnabled) {
        long start = System.nanoTime();
        byte[] result = delegate.get(name, key, valueLoader, ttl, timeToIdleEnabled);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache GET (with loader) | cache='{}' key='{}' | latency={}", name, new String(key), formatLatency(latencyMs));
        return result;
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key) {
        return delegate.retrieve(name, key);
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key, @Nullable Duration ttl) {
        return delegate.retrieve(name, key, ttl);
    }

    @Override
    public void put(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
        long start = System.nanoTime();
        delegate.put(name, key, value, ttl);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache PUT  | cache='{}' key='{}' size={}B ttl={} | latency={}",
                name, new String(key), value != null ? value.length : 0, ttl, formatLatency(latencyMs));
    }

    @Override
    public CompletableFuture<Void> store(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
        return delegate.store(name, key, value, ttl);
    }

    @Override
    public byte @Nullable [] putIfAbsent(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
        long start = System.nanoTime();
        byte[] result = delegate.putIfAbsent(name, key, value, ttl);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache PUT_IF_ABSENT | cache='{}' key='{}' | latency={}", name, new String(key), formatLatency(latencyMs));
        return result;
    }

    @Override
    public void evict(String name, byte[] key) {
        long start = System.nanoTime();
        delegate.evict(name, key);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache EVICT | cache='{}' key='{}' | latency={}", name, new String(key), formatLatency(latencyMs));
    }

    @Override
    public boolean evictIfPresent(String name, byte[] key) {
        long start = System.nanoTime();
        boolean result = delegate.evictIfPresent(name, key);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache EVICT_IF_PRESENT | cache='{}' key='{}' evicted={} | latency={}",
                name, new String(key), result, formatLatency(latencyMs));
        return result;
    }

    @Override
    public void clear(String name, byte[] pattern) {
        long start = System.nanoTime();
        delegate.clear(name, pattern);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache CLEAR | cache='{}' pattern='{}' | latency={}", name, new String(pattern), formatLatency(latencyMs));
    }

    @Override
    public boolean invalidate(String name, byte[] pattern) {
        long start = System.nanoTime();
        boolean result = delegate.invalidate(name, pattern);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] Cache INVALIDATE | cache='{}' pattern='{}' | latency={}", name, new String(pattern), formatLatency(latencyMs));
        return result;
    }

    @Override
    public void clearStatistics(String name) {
        delegate.clearStatistics(name);
    }

    @Override
    public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector cacheStatisticsCollector) {
        return new LoggingRedisCacheWriter(delegate.withStatisticsCollector(cacheStatisticsCollector));
    }

    @Override
    public CacheStatistics getCacheStatistics(String cacheName) {
        return delegate.getCacheStatistics(cacheName);
    }

    private static String formatLatency(double latencyMs) {
        if (latencyMs < 1.0) {
            return String.format("%.2f ms (%.0f µs)", latencyMs, latencyMs * 1000);
        }
        return String.format("%.2f ms", latencyMs);
    }
}