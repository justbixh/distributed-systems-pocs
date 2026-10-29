.PHONY: help build compile test run docker-up docker-down docker-infra docker-logs \
        docker-logs-app redis-cli redis-monitor redis-flush mysql-cli curl-demo clean

.DEFAULT_GOAL := help

CYAN  := \033[36m
GREEN := \033[32m
RESET := \033[0m

help: ## Show this help message
	@echo "$(CYAN)Spring Boot Redis POC - Available Commands:$(RESET)"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  $(GREEN)%-20s$(RESET) %s\n", $$1, $$2}'

# ── Maven ────────────────────────────────────────────────────────────────────
compile: ## Compile the project
	mvn compile

test: ## Run unit tests
	mvn test

build: ## Build jar skipping tests
	mvn clean package -DskipTests

package: ## Run tests and build production jar
	mvn clean package

run: ## Run Spring Boot locally (requires local MySQL + Redis)
	mvn spring-boot:run

clean: ## Clean maven build directory
	mvn clean

# ── Docker ───────────────────────────────────────────────────────────────────
docker-up: ## Build and start ALL services (MySQL, Redis, RedisInsight, App)
	docker compose up -d --build

docker-down: ## Stop and remove all containers
	docker compose down

docker-infra: ## Start only MySQL + Redis + RedisInsight (for local dev)
	docker compose up -d mysql redis redis-insight

docker-logs: ## Follow logs for all containers
	docker compose logs -f

docker-logs-app: ## Follow logs for the Spring Boot container
	docker compose logs -f app

# ── Redis ────────────────────────────────────────────────────────────────────
redis-cli: ## Open interactive redis-cli in the running Redis container
	docker exec -it redis-poc redis-cli

redis-monitor: ## Monitor Redis commands in real-time
	docker exec -it redis-poc redis-cli MONITOR
	# hit any API endpoint (via Swagger UI at http://localhost:8080/api/swagger-ui.html or curl) and watch in real time as Redis executes HSET, SMEMBERS, GET, SETEX, and INCR.

redis-flush: ## Flush ALL keys from Redis (destructive!)
	docker exec -it redis-poc redis-cli FLUSHALL

# ── MySQL ────────────────────────────────────────────────────────────────────
mysql-cli: ## Open interactive MySQL shell in the running container
	docker exec -it mysql-redispoc mysql -u appuser -papppass redispoc

# ── API Testing ──────────────────────────────────────────────────────────────
curl-demo: ## Run automated end-to-end curl API requests
	./curl-commands.sh

