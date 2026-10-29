> why this view matters what it is
``` 
redis-poc-app  | 2026-10-30 17:31:56 [http-nio-8080-exec-7] INFO  c.e.r.config.LoggingRedisCacheWriter - [REDIS] Cache HIT  | cache='product' key='redis-poc:product::1' | latency=1.37 ms
redis-poc-app  | 2026-10-30 17:31:56 [http-nio-8080-exec-7] INFO  c.e.redispoc.service.ProductService - [REDIS] INCR counter | key='product:views:1' newCount=10 | latency=1.88 ms
redis-poc-app  | 2026-10-30 17:33:45 [http-nio-8080-exec-8] INFO  c.e.redispoc.service.ProductService - [REDIS] GET counter | key='product:views:1' count=10 | latency=11.94 ms
```

---


### What Is This "View"?

In this POC, `views` represents a **product page view counter** (i.e. every time a user fetches `/v1/products/1`, the counter for that product increments by 1: 1, 2, 3 ... 10).

You can see where this happens in [`ProductController.java`](file:///Users/bishnu/projects/poc/spring-boot-redis-poc/src/main/java/com/example/redispoc/controller/ProductController.java#L44-L48):
```java
@GetMapping("/{id}")
public ResponseEntity<Product> getProduct(@PathVariable Long id) {
    Product product = productService.getProductById(id);
    productService.incrementAndGetViewCount(id); // <--- Triggers the INCR
    return ResponseEntity.ok(product);
}
```

---

### Why Does This Pattern Matter? (The Real-World Lesson)

It highlights one of the most critical patterns in distributed systems: **why a relational database (MySQL) should NOT be used for high-frequency writes/counters, and why Redis excels at it.**

#### 1. If You Did This in MySQL:
Every time a user visits a product page (10,000 views/sec during a flash sale), MySQL would have to execute:
```sql
UPDATE products SET views = views + 1 WHERE id = 1;
```
This causes major production bottlenecks:
* **Row-Level Lock Contention:** MySQL locks that specific row on every `UPDATE`. Concurrent requests queue up waiting for the lock, stalling read queries and causing connection pool exhaustion.
* **Write Disk I/O:** Every update forces disk writes (write-ahead log / redo log / InnoDB buffer flushes).
* **Cache Invalidation Disaster:** If you cached the product entity with its view count, **every single page view would invalidate your cache**, completely destroying the point of having a cache in the first place!

#### 2. How Redis Solves It (What Your Log Shows):

```text
[REDIS] Cache HIT  | cache='product' key='redis-poc:product::1' | latency=1.37 ms
[REDIS] INCR counter | key='product:views:1' newCount=10        | latency=1.88 ms
```

1. **Static Data is Cached:** The product details (name, price, description) are served from cache in **1.37 ms** without touching MySQL.
2. **Volatile Data is Decoupled:** The view count is kept separately as a lightweight atomic integer (`product:views:1`).
3. **Atomic `INCR` is Microsecond-Fast & Thread-Safe:**
   * Redis is single-threaded for command execution.
   * Native `INCR` increments the integer directly in RAM in nanoseconds/microseconds.
   * **Zero race conditions:** Even if 1,000 requests hit the server at the exact same millisecond, Redis guarantees every increment is counted correctly without deadlocks or row locks.

---

### Common Real-World Use Cases for This Pattern

| Use Case | Example | Why Redis `INCR` is Used |
| :--- | :--- | :--- |
| **Counters / Metrics** | Video views (YouTube/TikTok), post likes, page visits | Billions of updates/day without choking primary DB |
| **Rate Limiting** | "Max 100 requests per minute per IP" | Increment key with TTL, reject if count > 100 |
| **Inventory Reservations** | Flash sale countdown ("Only 50 items left") | Atomic decrement (`DECR`); stops overselling instantly |
| **Periodic DB Sync** | Batching updates | Redis absorbs 100,000 increments/sec; a background worker updates MySQL once every 5 minutes |

---

### Summary of What Your Logs Are Saying:

1. **`Cache HIT (1.37 ms)`**: The product data was served directly from Redis RAM — MySQL did 0 work.
2. **`INCR counter newCount=10 (1.88 ms)`**: Redis atomically bumped the view count from 9 to 10 in memory.
3. **`GET counter count=10`**: A separate endpoint (`GET /v1/products/1/views`) read the live counter without touching MySQL.