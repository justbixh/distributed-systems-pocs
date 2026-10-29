package com.example.redispoc.service;

import com.example.redispoc.config.CacheConfig;
import com.example.redispoc.exception.ResourceNotFoundException;
import com.example.redispoc.model.Product;
import com.example.redispoc.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Product service demonstrating multiple Redis patterns layered on top of MySQL:
 *  1. Spring Cache abstraction (@Cacheable, @CachePut, @CacheEvict) – Redis as cache
 *  2. MySQL via JPA (ProductRepository) – system of record
 *  3. Direct RedisTemplate operations – atomic view counters
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String VIEW_COUNT_KEY_PREFIX = "product:views:";

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Persist a new product to MySQL and evict the list caches so the next
     * read re-populates them with fresh data.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.PRODUCTS_CACHE,       allEntries = true),
            @CacheEvict(value = CacheConfig.PRODUCT_SEARCH_CACHE, allEntries = true)
    })
    public Product createProduct(Product product) {
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());

        long start = System.nanoTime();
        Product saved = productRepository.save(product);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

        log.info("[DATABASE] INSERT Product | id={} name='{}' | latency={}", saved.getId(), saved.getName(), formatLatency(latencyMs));
        return saved;
    }

    /**
     * Update a product in MySQL, refresh its individual cache entry, and
     * evict the list / search caches.
     */
    @Transactional
    @Caching(
            put   = @CachePut(value = CacheConfig.PRODUCT_CACHE, key = "#id"),
            evict = {
                    @CacheEvict(value = CacheConfig.PRODUCTS_CACHE,       allEntries = true),
                    @CacheEvict(value = CacheConfig.PRODUCT_SEARCH_CACHE, allEntries = true)
            }
    )
    public Product updateProduct(Long id, Product patch) {
        Product existing = findOrThrow(id);
        existing.setName(patch.getName());
        existing.setCategory(patch.getCategory());
        existing.setPrice(patch.getPrice());
        existing.setStock(patch.getStock());
        existing.setDescription(patch.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());

        long start = System.nanoTime();
        Product updated = productRepository.save(existing);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

        log.info("[DATABASE] UPDATE Product | id={} name='{}' | latency={}", id, updated.getName(), formatLatency(latencyMs));
        return updated;
    }

    /**
     * Delete a product from MySQL and evict all related caches.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.PRODUCT_CACHE,        key  = "#id"),
            @CacheEvict(value = CacheConfig.PRODUCTS_CACHE,       allEntries = true),
            @CacheEvict(value = CacheConfig.PRODUCT_SEARCH_CACHE, allEntries = true)
    })
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }

        long dbStart = System.nanoTime();
        productRepository.deleteById(id);
        double dbLatencyMs = (System.nanoTime() - dbStart) / 1_000_000.0;
        log.info("[DATABASE] DELETE Product | id={} | latency={}", id, formatLatency(dbLatencyMs));

        long redisStart = System.nanoTime();
        redisTemplate.delete(VIEW_COUNT_KEY_PREFIX + id);
        double redisLatencyMs = (System.nanoTime() - redisStart) / 1_000_000.0;
        log.info("[REDIS] DEL Counter | key='{}{}' | latency={}", VIEW_COUNT_KEY_PREFIX, id, formatLatency(redisLatencyMs));
    }

    // -------------------------------------------------------------------------
    // Read operations (cache-aside)
    // -------------------------------------------------------------------------

    /**
     * Return a single product. On cache miss, load from MySQL and populate cache.
     */
    @Cacheable(value = CacheConfig.PRODUCT_CACHE, key = "#id", unless = "#result == null")
    public Product getProductById(Long id) {
        log.info("[CACHE-MISS] Delegating to MySQL for product id={}", id);
        return findOrThrow(id);
    }

    /**
     * Return all products. On cache miss, load from MySQL.
     */
    @Cacheable(value = CacheConfig.PRODUCTS_CACHE, key = "'all'")
    public List<Product> getAllProducts() {
        log.info("[CACHE-MISS] Delegating to MySQL for all products");
        long start = System.nanoTime();
        List<Product> products = productRepository.findAll();
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[DATABASE] SELECT all products | count={} | latency={}", products.size(), formatLatency(latencyMs));
        return products;
    }

    /**
     * Return products by category. On cache miss, load from MySQL.
     */
    @Cacheable(value = CacheConfig.PRODUCT_SEARCH_CACHE, key = "#category")
    public List<Product> getProductsByCategory(String category) {
        log.info("[CACHE-MISS] Delegating to MySQL for category='{}'", category);
        long start = System.nanoTime();
        List<Product> products = productRepository.findByCategory(category);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[DATABASE] SELECT products by category='{}' | count={} | latency={}", category, products.size(), formatLatency(latencyMs));
        return products;
    }

    // -------------------------------------------------------------------------
    // Redis atomic counter (not cached through Spring Cache)
    // -------------------------------------------------------------------------

    /**
     * Atomically increment the view counter stored in Redis (INCR).
     * This demonstrates direct RedisTemplate usage for atomic operations
     * that would be expensive to do safely in MySQL.
     */
    public Long incrementAndGetViewCount(Long productId) {
        String key = VIEW_COUNT_KEY_PREFIX + productId;
        long start = System.nanoTime();
        Long count = redisTemplate.opsForValue().increment(key);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[REDIS] INCR counter | key='{}' newCount={} | latency={}", key, count, formatLatency(latencyMs));
        return count;
    }

    /**
     * Return the current view count for a product.
     */
    public Long getViewCount(Long productId) {
        String key = VIEW_COUNT_KEY_PREFIX + productId;
        long start = System.nanoTime();
        Object value = redisTemplate.opsForValue().get(key);
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        Long count = (value == null) ? 0L : (value instanceof Long l ? l : Long.parseLong(value.toString()));
        log.info("[REDIS] GET counter | key='{}' count={} | latency={}", key, count, formatLatency(latencyMs));
        return count;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Product findOrThrow(Long id) {
        long start = System.nanoTime();
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[DATABASE] SELECT product by id={} | latency={}", id, formatLatency(latencyMs));
        return product;
    }

    private static String formatLatency(double latencyMs) {
        if (latencyMs < 1.0) {
            return String.format("%.2f ms (%.0f µs)", latencyMs, latencyMs * 1000);
        }
        return String.format("%.2f ms", latencyMs);
    }
}
