package com.example.redispoc.controller;

import com.example.redispoc.model.Product;
import com.example.redispoc.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for Product resource.
 * MySQL is the system of record; Redis provides transparent caching.
 */
@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product management API (MySQL + Redis cache)")
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "Create a new product")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Product created"), @ApiResponse(responseCode = "400", description = "Invalid input", content = @Content)})
    @PostMapping
    public ResponseEntity<Product> createProduct(@Valid @RequestBody Product product) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(productService.createProduct(product));
    }

    @Operation(summary = "Get product by ID")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Found the product"), @ApiResponse(responseCode = "404", description = "Product not found", content = @Content)})
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@Parameter(description = "Product ID") @PathVariable Long id) {
        Product product = productService.getProductById(id);
        productService.incrementAndGetViewCount(id);
        return ResponseEntity.ok(product);
    }

    @Operation(summary = "List all products")
    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @Operation(summary = "Search products by category")
    @GetMapping("/search")
    public ResponseEntity<List<Product>> getByCategory(@Parameter(description = "Category name") @RequestParam String category) {
        return ResponseEntity.ok(productService.getProductsByCategory(category));
    }

    @Operation(summary = "Update a product")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Product updated"), @ApiResponse(responseCode = "404", description = "Product not found", content = @Content)})
    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @Valid @RequestBody Product product) {
        return ResponseEntity.ok(productService.updateProduct(id, product));
    }

    @Operation(summary = "Delete a product")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get product view count (Redis atomic counter)")
    @GetMapping("/{id}/views")
    public ResponseEntity<Map<String, Object>> getViewCount(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("productId", id, "views", productService.getViewCount(id)));
    }
}
