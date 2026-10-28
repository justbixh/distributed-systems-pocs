package com.example.redispoc.service;

import com.example.redispoc.config.CacheConfig;
import com.example.redispoc.exception.ResourceNotFoundException;
import com.example.redispoc.model.Product;
import com.example.redispoc.repository.ProductRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Product service demonstrating multiple Redis patterns:
 * 1. Spring Cache abstraction (@Cacheable, @CachePut, @CacheEvict)
 * 2. Spring Data Redis repository (RedisHash)
 * 3. Direct RedisTemplate operations (atomic counter example)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRedisRepository productRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String VIEW_COUNT_KEY_PREFIX = "product:views:";

    /**
     * Create a new product. Evicts the products list cache.
     */
    @CacheEvict(value = CacheConfig.PRODUCTS_CACHE, allEntries = true)
    public Product createProduct(Product product) {
        product.setId(UUID.randomUUID().toString());
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        Product saved = productRepository.save(product);
        log.info("Created product with id={}", saved.getId());
        return saved;
    }

    /**
     * Get a product by ID. Result cached per product ID.
     */
    @Cacheable(value = CacheConfig.PRODUCT_CACHE, key = "#id", unless = "#result == null")
    public Product getProductById(String id) {
        log.debug("Fetching product id={} from Redis repository", id);
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }

    /**
     * Get all products. Result cached as a list.
     */
    @Cacheable(value = CacheConfig.PRODUCTS_CACHE, key = "'all'")
    public List<Product> getAllProducts() {
        log.debug("Fetching all products from Redis repository");
        List<Product> list = new ArrayList<>();
        productRepository.findAll().forEach(list::add);
        return list;
    }

    /**
     * Search products by category.
     */
    @Cacheable(value = CacheConfig.PRODUCT_SEARCH_CACHE, key = "#category")
    public List<Product> getProductsByCategory(String category) {
        log.debug("Fetching products by category={}", category);
        return productRepository.findByCategory(category);
    }

    /**
     * Update a product. Updates the cache entry and evicts the list cache.
     */
    @Caching(
        put  = @CachePut(value = CacheConfig.PRODUCT_CACHE, key = "#id"),
        evict = @CacheEvict(value = {CacheConfig.PRODUCTS_CACHE, CacheConfig.PRODUCT_SEARCH_CACHE}, allEntries = true)
    )
    public Product updateProduct(String id, Product product) {
        Product existing = getProductById(id);
        existing.setName(product.getName());
        existing.setCategory(product.getCategory());
        existing.setPrice(product.getPrice());
        existing.setStock(product.getStock());
        existing.setDescription(product.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());
        Product updated = productRepository.save(existing);
        log.info("Updated product id={}", id);
        return updated;
    }

    /**
     * Delete a product. Evicts all related caches.
     */
    @Caching(evict = {
        @CacheEvict(value = CacheConfig.PRODUCT_CACHE, key = "#id"),
        @CacheEvict(value = CacheConfig.PRODUCTS_CACHE, allEntries = true),
        @CacheEvict(value = CacheConfig.PRODUCT_SEARCH_CACHE, allEntries = true)
    })
    public void deleteProduct(String id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }
        productRepository.deleteById(id);
        // Cleanup view counter
        redisTemplate.delete(VIEW_COUNT_KEY_PREFIX + id);
        log.info("Deleted product id={}", id);
    }

    /**
     * Increment and return view count using atomic Redis INCR operation.
     * Demonstrates direct RedisTemplate usage for atomic counters.
     */
    public Long incrementAndGetViewCount(String productId) {
        String key = VIEW_COUNT_KEY_PREFIX + productId;
        Long count = redisTemplate.opsForValue().increment(key);
        log.debug("Product id={} view count={}", productId, count);
        return count;
    }

    /**
     * Get current view count for a product.
     */
    public Long getViewCount(String productId) {
        String key = VIEW_COUNT_KEY_PREFIX + productId;
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) return 0L;
        return value instanceof Long l ? l : Long.parseLong(value.toString());
    }
}
