#!/usr/bin/env bash
# ==============================================================================
# Spring Boot Redis + MySQL POC - Curl Commands
#
# You can:
#  1. Copy and paste any curl command below directly into your terminal.
#  2. Run this script directly to execute an end-to-end test flow:
#       ./curl-commands.sh
# ==============================================================================

BASE_URL="${BASE_URL:-http://localhost:8080/api}"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Helper for formatted output if run directly
step() {
    echo -e "\n${BLUE}==>${NC} ${CYAN}$1${NC}"
}

# ==============================================================================
# 1. HEALTH CHECKS & OBSERVABILITY
# ==============================================================================

# Health check (MySQL + Redis status)
# curl -s -X GET "${BASE_URL}/actuator/health" | jq .

# Inspect active cache managers and caches
# curl -s -X GET "${BASE_URL}/actuator/caches" | jq .

# Prometheus metrics
# curl -s -X GET "${BASE_URL}/actuator/prometheus"

# ==============================================================================
# 2. PRODUCT CRUD & CACHING OPERATIONS
# ==============================================================================

# --- Create Product 1 (Mechanical Keyboard) ---
# Evicts "products" and "productSearch" caches
# curl -s -X POST "${BASE_URL}/v1/products" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "name": "Mechanical Keyboard",
#     "category": "electronics",
#     "price": 149.99,
#     "stock": 50,
#     "description": "Tenkeyless mechanical keyboard with Cherry MX switches"
#   }' | jq .

# --- Create Product 2 (Wireless Mouse) ---
# curl -s -X POST "${BASE_URL}/v1/products" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "name": "Wireless Mouse",
#     "category": "electronics",
#     "price": 79.99,
#     "stock": 100,
#     "description": "Ergonomic wireless mouse with 4000 DPI sensor"
#   }' | jq .

# --- Create Product 3 (Coffee Mug) ---
# curl -s -X POST "${BASE_URL}/v1/products" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "name": "Ceramic Coffee Mug",
#     "category": "kitchen",
#     "price": 14.50,
#     "stock": 200,
#     "description": "12oz ceramic mug, dishwasher safe"
#   }' | jq .

# --- Get Product by ID (Cacheable, 15m TTL) ---
# First call: cache miss (queries MySQL, saves to Redis)
# Second call: cache hit (served from Redis memory in <1ms)
# Also increments the Redis atomic view counter
# curl -s -X GET "${BASE_URL}/v1/products/1" | jq .

# --- Get Product View Count (Atomic Redis Counter) ---
# Direct RedisTemplate GET (INCR counter)
# curl -s -X GET "${BASE_URL}/v1/products/1/views" | jq .

# --- List All Products (Cacheable, 5m TTL) ---
# Cached under "redis-poc:products:all"
# curl -s -X GET "${BASE_URL}/v1/products" | jq .

# --- Search Products by Category (Cacheable, 2m TTL) ---
# Cached under "redis-poc:productSearch:electronics"
# curl -s -X GET "${BASE_URL}/v1/products/search?category=electronics" | jq .

# --- Update Product ---
# Updates MySQL, updates "product:1" cache, evicts "products" & "productSearch"
# curl -s -X PUT "${BASE_URL}/v1/products/1" \
#   -H "Content-Type: application/json" \
#   -d '{
#     "name": "Mechanical Keyboard Pro",
#     "category": "electronics",
#     "price": 169.99,
#     "stock": 45,
#     "description": "TKL RGB backlit mechanical keyboard with hot-swappable switches"
#   }' | jq .

# --- Delete Product ---
# Deletes from MySQL, evicts all related caches, deletes Redis view counter
# curl -s -X DELETE "${BASE_URL}/v1/products/3" -w "\nHTTP Status: %{http_code}\n"


# ==============================================================================
# AUTOMATED DEMO EXECUTION (when running `./curl-commands.sh`)
# ==============================================================================

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo -e "${YELLOW}======================================================${NC}"
    echo -e "${YELLOW} Running Spring Boot Redis POC End-to-End API Demo   ${NC}"
    echo -e "${YELLOW} Target: ${BASE_URL}${NC}"
    echo -e "${YELLOW}======================================================${NC}"

    # Check curl and jq
    if ! command -v jq &> /dev/null; then
        echo -e "${YELLOW}Note: 'jq' not found. Installing jq will give formatted JSON output.${NC}"
        FORMAT_CMD="cat"
    else
        FORMAT_CMD="jq ."
    fi

    step "1. Health Check (Verifying MySQL & Redis connectivity)"
    curl -s -X GET "${BASE_URL}/actuator/health" | $FORMAT_CMD

    step "2. Create Product 1 (Mechanical Keyboard)"
    CREATE_RES=$(curl -s -X POST "${BASE_URL}/v1/products" \
      -H "Content-Type: application/json" \
      -d '{
        "name": "Mechanical Keyboard",
        "category": "electronics",
        "price": 149.99,
        "stock": 50,
        "description": "Tenkeyless mechanical keyboard with Cherry MX switches"
      }')
    echo "$CREATE_RES" | $FORMAT_CMD
    PROD_ID=$(echo "$CREATE_RES" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    PROD_ID=${PROD_ID:-1}

    step "3. Create Product 2 (Wireless Mouse)"
    curl -s -X POST "${BASE_URL}/v1/products" \
      -H "Content-Type: application/json" \
      -d '{
        "name": "Wireless Mouse",
        "category": "electronics",
        "price": 79.99,
        "stock": 100,
        "description": "Ergonomic wireless mouse with 4000 DPI sensor"
      }' | $FORMAT_CMD

    step "4. Get Product by ID (ID: ${PROD_ID}) - [1st call: Cache Miss -> Populates Redis]"
    curl -s -X GET "${BASE_URL}/v1/products/${PROD_ID}" | $FORMAT_CMD

    step "5. Get Product by ID (ID: ${PROD_ID}) - [2nd call: Cache Hit -> Served from Redis]"
    curl -s -X GET "${BASE_URL}/v1/products/${PROD_ID}" | $FORMAT_CMD

    step "6. View Counter for Product ${PROD_ID} (Incremented on each GET)"
    curl -s -X GET "${BASE_URL}/v1/products/${PROD_ID}/views" | $FORMAT_CMD

    step "7. Search Products by Category ('electronics')"
    curl -s -X GET "${BASE_URL}/v1/products/search?category=electronics" | $FORMAT_CMD

    step "8. List All Products"
    curl -s -X GET "${BASE_URL}/v1/products" | $FORMAT_CMD

    step "9. Update Product ${PROD_ID} (Updates DB, updates single cache, evicts lists)"
    curl -s -X PUT "${BASE_URL}/v1/products/${PROD_ID}" \
      -H "Content-Type: application/json" \
      -d '{
        "name": "Mechanical Keyboard RGB",
        "category": "electronics",
        "price": 169.99,
        "stock": 45,
        "description": "Updated with per-key RGB backlighting"
      }' | $FORMAT_CMD

    step "10. Inspect Actuator Caches (Shows active Redis caches)"
    curl -s -X GET "${BASE_URL}/actuator/caches" | $FORMAT_CMD

    echo -e "\n${GREEN}Demo completed successfully!${NC}"
fi

