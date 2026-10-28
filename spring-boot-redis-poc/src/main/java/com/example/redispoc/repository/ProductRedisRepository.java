package com.example.redispoc.repository;

import com.example.redispoc.model.Product;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data Redis repository for Product.
 * Provides CRUD operations backed by Redis Hash.
 */
@Repository
public interface ProductRedisRepository extends CrudRepository<Product, String> {

    List<Product> findByCategory(String category);

    List<Product> findByName(String name);
}
