# Spring Boot Redis POC — MySQL + Redis Cache

A production-grade proof-of-concept demonstrating the canonical pattern of using **MySQL as the system of record** and **Redis as a transparent cache layer** in a Spring Boot 4 application.

---

## Tech Stack

| Component | Version |
|---|---|
| Spring Boot | 4.0.0 |
| Java | 17 |
| Spring Data JPA / Hibernate | 6.x |
| MySQL | 8.4 |
| Spring Data Redis / Lettuce | 4.0.0 |
| Redis Server | 7.4-alpine |
| Micrometer / Prometheus | Latest |
| SpringDoc OpenAPI | 3.0.0 |

---

## Architecture

```
                            HTTP Client
                                 │
                                 ▼
                          ProductController
                                 │
                                 ▼
                           ProductService
  ┌───────────────────────────────────────────────────────────────┐
  │                                                               │
  │  @Cacheable / @CachePut / @CacheEvict                         │
  │         ↕                                                     │
  │      Redis (cache)          MySQL (source of truth)           │
  │      • product:{id}    ←→   • products table                  │
  │      • products:all         • JPA / Hibernate                 │
  │      • productSearch:*                                        │
  │                                                               │
  │  RedisTemplate (direct)                                       │
  │      • product:views:{id}   ← INCR atomic counter             │
  └───────────────────────────────────────────────────────────────┘
```

**Read path (cache-aside):**
1. Request hits service method annotated with `@Cacheable`.
2. Spring checks Redis for the key — on **HIT** the result is returned immediately (sub-millisecond), MySQL is not queried.
3. On **MISS** the method body executes, fetches from MySQL via JPA, and populates the Redis key with the configured TTL.

**Write path:**
1. `createProduct` / `updateProduct` / `deleteProduct` persist to MySQL first (`@Transactional`).
2. Cache annotations (`@CacheEvict`, `@CachePut`) keep Redis consistent with the database.

---

## Project Structure

```
spring-boot-redis-poc/
├── Makefile
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/redispoc/
    │   │   ├── RedisPocApplication.java
    │   │   ├── config/
    │   │   │   ├── RedisConfig.java       # RedisTemplate with JSON value serialization
    │   │   │   └── CacheConfig.java       # RedisCacheManager with per-cache TTLs
    │   │   ├── model/
    │   │   │   └── Product.java           # @Entity — JPA / MySQL
    │   │   ├── repository/
    │   │   │   └── ProductRepository.java # JpaRepository<Product, Long>
    │   │   ├── service/
    │   │   │   └── ProductService.java    # Cache-aside + atomic counters
    │   │   ├── controller/
    │   │   │   └── ProductController.java # REST API
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java
    │   │       └── ResourceNotFoundException.java
    │   └── resources/
    │       ├── application.yml            # Dev config (H2 can be swapped in for tests)
    │       └── application-prod.yml       # Production overrides
    └── test/
        ├── java/com/example/redispoc/
        │   ├── RedisPocApplicationTests.java   # Spring context smoke test (H2 + no Redis)
        │   └── service/ProductServiceTest.java # Unit tests with Mockito
        └── resources/
            ├── application.yml                  # H2 + cache.type=none for unit tests
            └── mockito-extensions/
                └── org.mockito.plugins.MockMaker # subclass mock-maker for JDK 21
```

---

## Key Design Decisions

### 1. MySQL — System of Record
Every product is durably persisted to MySQL via **Spring Data JPA / Hibernate**. Indexes on `category` and `name` are declared at the entity level and created automatically by Hibernate.

### 2. Redis — Cache Layer Only (cache-aside pattern)
Redis holds short-lived JSON copies of frequently read data. Three named caches are configured with independent TTLs:

| Cache name | TTL | Evicted when |
|---|---|---|
| `product` | 15 min | product updated or deleted |
| `products` | 5 min | any product created, updated, or deleted |
| `productSearch` | 2 min | any product created, updated, or deleted |

### 3. JSON Serialization
Redis keys are serialized as strings (`StringRedisSerializer`), while cached values and object templates serialize data as standard JSON via `RedisSerializer.json()`. This keeps payloads inspection-friendly in tools like RedisInsight while supporting complex domain models.

### 4. Atomic View Counter
View counts are stored as native Redis integers and incremented with the `INCR` command via `RedisTemplate`, avoiding the read-modify-write race condition inherent in a SQL `UPDATE ... SET views = views + 1` approach.

### 5. Lettuce Connection Pooling
Pool is backed by `commons-pool2`. Settings are tuned per-environment:

```yaml
lettuce:
  pool:
    max-active: 8    # dev  (16 in prod)
    max-idle:   4    # dev  ( 8 in prod)
    min-idle:   1    # dev  ( 2 in prod)
```

### 6. Real-Time Latency Logging (`[REDIS]` vs `[DATABASE]`)
To clearly observe performance differences between cache hits, cache misses, and database operations, real-time nanosecond-precision latency logging is built in:
* **Redis Cache Operations**: Custom `LoggingRedisCacheWriter` logs cache hits, misses, puts, evictions, and patterns with microsecond/millisecond latency (e.g. `[REDIS] Cache HIT | latency=0.79 ms (786 µs)`).
* **Database Operations**: `ProductService` logs exact execution times for SQL `SELECT`, `INSERT`, `UPDATE`, and `DELETE` statements (e.g. `[DATABASE] SELECT product by id=1 | latency=11.14 ms`).
* **Atomic Redis Ops**: `INCR` and `GET` counters log direct RedisTemplate round-trip latency.

---

## Quick Start

### Option A — Full Docker stack (recommended)

```bash
make docker-up
```

Starts **MySQL 8.4**, **Redis 7.4**, **RedisInsight**, and the **Spring Boot app** together.

| Service | URL |
|---|---|
| API | http://localhost:8080/api/swagger-ui.html |
| RedisInsight | http://localhost:5540 |
| Actuator health | http://localhost:8080/api/actuator/health |

### Option B — Infrastructure only, app runs locally

```bash
make docker-infra   # starts MySQL + Redis + RedisInsight
make run            # runs Spring Boot via mvn spring-boot:run
```

Requires environment variables or defaults in `application.yml`:

```
MYSQL_HOST=localhost  MYSQL_PORT=3306  MYSQL_DB=redispoc
MYSQL_USER=root       MYSQL_PASSWORD=secret
REDIS_HOST=localhost  REDIS_PORT=6379
```

---

## Makefile Reference

```
make help            Show this message
make compile         Compile source files
make test            Run all unit tests
make build           Package jar (skip tests)
make package         Run tests + package jar
make run             Run Spring Boot locally
make clean           Clean Maven build directory

make docker-up       Build + start ALL containers
make docker-down     Stop + remove all containers
make docker-infra    Start MySQL + Redis + RedisInsight only
make docker-logs     Tail logs (all containers)
make docker-logs-app Tail logs (app only)

make redis-cli       Interactive redis-cli
make redis-monitor   Real-time Redis command stream (MONITOR)
make redis-flush     FLUSHALL — clear all Redis keys

make mysql-cli       Interactive MySQL shell (appuser)
```

---

## REST API

Base URL: `http://localhost:8080/api/v1/products`

| Method | Path | Description | Cache behaviour |
|---|---|---|---|
| `POST` | `/` | Create product | Evicts `products` + `productSearch` |
| `GET` | `/{id}` | Get by ID (increments view counter) | **Cached** in `product` (15 min) |
| `GET` | `/` | List all | **Cached** in `products` (5 min) |
| `GET` | `/search?category=…` | Filter by category | **Cached** in `productSearch` (2 min) |
| `PUT` | `/{id}` | Update | Refreshes `product` cache entry; evicts list/search |
| `DELETE` | `/{id}` | Delete | Evicts all related caches + deletes view counter key |
| `GET` | `/{id}/views` | View count | Direct Redis `GET` (not Spring Cache) |

### Automated Curl Script & HTTP File

You can run the full API demo script or copy individual commands from [`curl-commands.sh`](file:///Users/bishnu/projects/poc/spring-boot-redis-poc/curl-commands.sh):

```bash
make curl-demo
# or
./curl-commands.sh
```

An IDE-friendly [`requests.http`](file:///Users/bishnu/projects/poc/spring-boot-redis-poc/requests.http) file is also provided for 1-click execution in IntelliJ IDEA or VS Code.

#### Summary of operations covered in the file:

| Category | Endpoint | Sample Command |
| :--- | :--- | :--- |
| **Health** | `GET /api/actuator/health` | `curl -s "${BASE_URL}/actuator/health" \| jq .` |
| **Caches** | `GET /api/actuator/caches` | `curl -s "${BASE_URL}/actuator/caches" \| jq .` |
| **Create** | `POST /api/v1/products` | `curl -s -X POST "${BASE_URL}/v1/products" -H "Content-Type: application/json" -d '{"name":"Mechanical Keyboard","category":"electronics","price":149.99,"stock":50}' \| jq .` |
| **Get (Cached)** | `GET /api/v1/products/1` | `curl -s "${BASE_URL}/v1/products/1" \| jq .` *(1st: DB load; 2nd: Redis hit)* |
| **View Count** | `GET /api/v1/products/1/views` | `curl -s "${BASE_URL}/v1/products/1/views" \| jq .` *(Atomic Redis INCR)* |
| **List All** | `GET /api/v1/products` | `curl -s "${BASE_URL}/v1/products" \| jq .` |
| **Search** | `GET /api/v1/products/search?category=electronics` | `curl -s "${BASE_URL}/v1/products/search?category=electronics" \| jq .` |
| **Update** | `PUT /api/v1/products/1` | `curl -s -X PUT "${BASE_URL}/v1/products/1" -H "Content-Type: application/json" -d '{"name":"Mechanical Keyboard Pro","category":"electronics","price":169.99,"stock":45}' \| jq .` |
| **Delete** | `DELETE /api/v1/products/1` | `curl -s -X DELETE "${BASE_URL}/v1/products/1"` |

### Example — create a product

```bash
curl -s -X POST http://localhost:8080/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{
    "name":        "Mechanical Keyboard",
    "category":    "electronics",
    "price":       149.99,
    "stock":       50,
    "description": "TKL, Cherry MX Red switches"
  }' | jq
```

### Example — observe caching

```bash
# First call → MySQL query, Redis miss
curl http://localhost:8080/api/v1/products/1

# Watch Redis in a separate terminal — second call is cache-hit only
make redis-monitor
curl http://localhost:8080/api/v1/products/1
```

---

## Observability

| Endpoint | Description |
|---|---|
| `/api/actuator/health` | App, MySQL, and Redis health |
| `/api/actuator/caches` | List of active cache managers and keys |
| `/api/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/api/swagger-ui.html` | OpenAPI / Swagger UI |

---

## Running Tests

Unit tests use **H2 in-memory** (no MySQL or Redis required):

```bash
make test
# or
mvn test
```

The Spring context smoke test (`RedisPocApplicationTests`) boots the full context using H2 with `spring.cache.type=none`, so no external services are required in CI.
