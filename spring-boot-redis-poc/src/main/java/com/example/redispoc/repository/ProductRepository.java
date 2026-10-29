package com.example.redispoc.repository;

import com.example.redispoc.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for Product.
 * Provides CRUD + query operations backed by MySQL.
 * Redis caching is applied at the service layer via @Cacheable.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategory(String category);

    List<Product> findByName(String name);
}

