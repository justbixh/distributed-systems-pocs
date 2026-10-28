package com.example.redispoc.service;

import com.example.redispoc.exception.ResourceNotFoundException;
import com.example.redispoc.model.Product;
import com.example.redispoc.repository.ProductRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRedisRepository productRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private ProductService productService;

    private Product testProduct;

    @BeforeEach
    void setUp() {
        testProduct = Product.builder()
                .name("Test Product")
                .category("electronics")
                .price(BigDecimal.valueOf(99.99))
                .stock(10)
                .description("A test product")
                .build();
    }

    @Test
    void createProduct_ShouldAssignIdAndTimestamps() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product created = productService.createProduct(testProduct);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void getProductById_WhenExists_ShouldReturnProduct() {
        testProduct.setId("test-id");
        when(productRepository.findById("test-id")).thenReturn(Optional.of(testProduct));

        Product found = productService.getProductById("test-id");

        assertThat(found).isEqualTo(testProduct);
    }

    @Test
    void getProductById_WhenNotExists_ShouldThrowException() {
        when(productRepository.findById("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById("non-existent"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("non-existent");
    }

    @Test
    void deleteProduct_WhenNotExists_ShouldThrowException() {
        when(productRepository.existsById("non-existent")).thenReturn(false);

        assertThatThrownBy(() -> productService.deleteProduct("non-existent"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incrementAndGetViewCount_ShouldIncrementCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("product:views:prod-1")).thenReturn(5L);

        Long count = productService.incrementAndGetViewCount("prod-1");

        assertThat(count).isEqualTo(5L);
    }
}
