# Spring Boot 4 + Redis POC (Production-Grade)

A production-ready proof-of-concept demonstrating Redis integration with Spring Boot 4.x and Java 17.

## Tech Stack

| Component | Version |
|---|---|
| Spring Boot | 4.0.0 |
| Java | 17 (Jakarta EE 11 baseline) |
| Spring Data Redis | 2025.x |
| Lettuce (Redis client) | 6.x |
| Redis Server | 7.4-alpine |
| Micrometer / Prometheus | Latest |
| SpringDoc OpenAPI | 3.0.0 |

---

## Key Production Features

1. **Lettuce Connection Pooling:**
   - Powered by `commons-pool2`.
   - Tuned with `max-active`, `max-idle`, `min-idle`, and `max-wait`.
   - Socket keep-alive and auto-reconnect configured.

2. **Spring Cache Abstraction:**
   - Declarative caching using `@Cacheable`, `@CachePut`, and `@CacheEvict`.
   - Per-cache TTL overrides (e.g., 5 min for lists, 15 min for single items, 2 min for search).
   - Polymorphic Jackson 2 JSON serialization with `JavaTimeModule` support.

3. **Spring Data Redis Repository:**
   - Hash mapping with `@RedisHash(value = "product", timeToLive = 3600L)`.
   - Secondary indices on `name` and `category` via `@Indexed`.

4. **Direct RedisTemplate Atomic Operations:**
   - Demonstrates high-throughput counters using `INCR` via `redisTemplate.opsForValue().increment(...)`.

5. **Observability & Health:**
   - Actuator enabled with `/actuator/health` (includes Redis connectivity check).
   - Prometheus metrics export via `/actuator/prometheus`.
   - Caches management endpoint at `/actuator/caches`.

6. **Error Handling & API Docs:**
   - RFC 7807 `ProblemDetail` responses for standardized errors.
   - OpenAPI / Swagger UI at `/api/swagger-ui.html`.

---

## Project Structure

```
spring-boot-redis-poc/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/example/redispoc/
    │   │   ├── RedisPocApplication.java
    │   │   ├── config/
    │   │   │   ├── RedisConfig.java
    │   │   │   └── CacheConfig.java
    │   │   ├── model/Product.java
    │   │   ├── repository/ProductRedisRepository.java
    │   │   ├── service/ProductService.java
    │   │   ├── controller/ProductController.java
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java
    │   │       └── ResourceNotFoundException.java
    │   └── resources/
    │       ├── application.yml
    │       └── application-prod.yml
    └── test/
        └── java/com/example/redispoc/
            ├── RedisPocApplicationTests.java
            └── service/ProductServiceTest.java
```

---

## Quick Start

### 1. Run with Docker Compose (Recommended)

Start Redis and RedisInsight:
```bash
docker compose up redis redis-insight -d
```
Access RedisInsight at: [http://localhost:5540](http://localhost:5540)

To start the full stack including the Spring Boot application:
```bash
docker compose up --build
```

### 2. Run Application Locally

Ensure Redis is running:
```bash
docker run -d -p 6379:6379 --name redis redis:7.4-alpine
```

Build and run:
```bash
mvn clean spring-boot:run
```

---

## REST API Endpoints

Base URL: `http://localhost:8080/api`

| Method | Endpoint | Description | Cache Behavior |
|---|---|---|---|
| `POST` | `/v1/products` | Create product | Evicts `products` cache |
| `GET` | `/v1/products/{id}` | Get product by ID & increments view count | Cached (`product`, 15m TTL) |
| `GET` | `/v1/products` | List all products | Cached (`products`, 5m TTL) |
| `GET` | `/v1/products/search?category={cat}` | Search by category | Cached (`productSearch`, 2m TTL) |
| `PUT` | `/v1/products/{id}` | Update product | Updates `product` cache, evicts list/search |
| `DELETE` | `/v1/products/{id}` | Delete product | Evicts all related caches & deletes view key |
| `GET` | `/v1/products/{id}/views` | Get product view counter | Direct Redis `GET` counter |

---

## Actuator & Monitoring Endpoints

| Endpoint | Description |
|---|---|
| `http://localhost:8080/api/actuator/health` | Application & Redis health check status |
| `http://localhost:8080/api/actuator/prometheus` | Prometheus scrape endpoint |
| `http://localhost:8080/api/actuator/caches` | Cache manager and cached keys inspection |
| `http://localhost:8080/api/swagger-ui.html` | Swagger UI documentation |
