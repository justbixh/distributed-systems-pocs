## Curl commands

> Make sure the application is running.
> If not then `make docker-up`

```bash
export BASE_URL=http://localhost:8080/api
```

**Health and observability**

```bash
curl -s "$BASE_URL/actuator/health" | jq .
```

```bash
curl -s "$BASE_URL/actuator/caches" | jq .
```

```bash
curl -s "$BASE_URL/actuator/prometheus"
```

**Create products** (evicts the `products` and `productSearch` caches)

```bash
curl -s -X POST "$BASE_URL/v1/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Mechanical Keyboard","category":"electronics","price":149.99,"stock":50,"description":"Tenkeyless mechanical keyboard with Cherry MX switches"}' | jq .
```

```bash
curl -s -X POST "$BASE_URL/v1/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Wireless Mouse","category":"electronics","price":79.99,"stock":100,"description":"Ergonomic wireless mouse with 4000 DPI sensor"}' | jq .
```

```bash
curl -s -X POST "$BASE_URL/v1/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Ceramic Coffee Mug","category":"kitchen","price":14.50,"stock":200,"description":"12oz ceramic mug, dishwasher safe"}' | jq .
```

**Read** (first call is a cache miss, second is a hit)

```bash
curl -s "$BASE_URL/v1/products/1" | jq .
```

```bash
curl -s "$BASE_URL/v1/products/1/views" | jq .
```

```bash
curl -s "$BASE_URL/v1/products" | jq .
```

```bash
curl -s "$BASE_URL/v1/products/search?category=electronics" | jq .
```

**Update**

```bash
curl -s -X PUT "$BASE_URL/v1/products/1" \
  -H "Content-Type: application/json" \
  -d '{"name":"Mechanical Keyboard Pro","category":"electronics","price":169.99,"stock":45,"description":"TKL RGB backlit mechanical keyboard with hot-swappable switches"}' | jq .
```

**Delete**

```bash
curl -s -X DELETE "$BASE_URL/v1/products/3" -w "\nHTTP Status: %{http_code}\n"
```