package com.example.redispoc.service;

import com.example.redispoc.exception.ResourceNotFoundException;
import com.example.redispoc.model.Product;
import com.example.redispoc.repository.ProductRepository;
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
    private ProductRepository productRepository;

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
    void createProduct_ShouldAssignTimestamps() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        Product created = productService.createProduct(testProduct);

        assertThat(created.getId()).isEqualTo(1L);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void getProductById_WhenExists_ShouldReturnProduct() {
        testProduct.setId(1L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(testProduct));

        Product found = productService.getProductById(1L);

        assertThat(found).isEqualTo(testProduct);
    }

    @Test
    void getProductById_WhenNotExists_ShouldThrowException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProductById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void deleteProduct_WhenNotExists_ShouldThrowException() {
        when(productRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> productService.deleteProduct(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void incrementAndGetViewCount_ShouldIncrementCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("product:views:1")).thenReturn(5L);

        Long count = productService.incrementAndGetViewCount(1L);

        assertThat(count).isEqualTo(5L);
    }
}
