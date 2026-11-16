# simple-kafka-outbox

## Problem

You save an order to the database and then publish an event to Kafka. Two systems, no shared transaction. If the app crashes (or Kafka is down) between the two steps, you get an order with no event, or an event for an order that was rolled back.

## The whole idea in 30 seconds

Don't write to Kafka in the request. Write the event to an **outbox table in the same database transaction** as the order. A separate **relay** reads the outbox and publishes to Kafka.

**Before: dual write.** Two systems, no shared transaction, so a crash between the two steps loses or invents events.

```
POST /orders ─┬─► 1. INSERT order into DB ✔
              │        ✖ crash here: order saved, event never sent
              └─► 2. send event to Kafka
                       ✖ Kafka down, or DB rolls back later: event with no order
```

**After: outbox.** The request only touches the database. A separate relay does the Kafka part.

```
POST /orders ─► ┌─ ONE DB transaction ───────────────┐
                │  INSERT orders                     │   both rows commit,
                │  INSERT outbox  (sent = false)     │   or neither does
                └────────────────────────────────────┘
                                 │
                   OutboxRelay polls every 500 ms
                                 │  1. read unsent rows
                                 │  2. send to Kafka
                                 │  3. mark sent = true
                                 ▼
                         topic "orders" ─► Consumer
```

- Transaction rolls back: no order, no event.
- Kafka is down: the row stays unsent and is retried until it goes through.
- App dies after the send but before "mark sent": the event is published again. **Delivery is at-least-once**, so consumers must handle duplicates.

## Code tour

- `OrderService.java` inserts the order and the outbox row in one `@Transactional` method. Item `boom` throws after both inserts, to show the rollback.
- `OutboxRelay.java` polls unsent rows, sends each to Kafka, marks it sent.
- `Consumer.java` logs what arrives on `orders`.
- `schema.sql` creates the tables in an in-memory H2 database (lost on restart).

## How to run

```bash
docker compose up -d          # start Kafka on localhost:9092
./mvnw spring-boot:run        # start the app on :8080
./mvnw test                   # automated check (in-memory Kafka and H2, no Docker)
```

## Experiments

```bash
curl -X POST 'localhost:8080/orders?item=pizza'   # app logs "received: pizza"
curl -X POST 'localhost:8080/orders?item=boom'    # 500, nothing is published
```

To see the outbox working: `docker compose stop`, create an order (it succeeds), then `docker compose start`. The event arrives once the relay can reach Kafka.

## Scaling the relay: `FOR UPDATE SKIP LOCKED`

With one relay, the plain `SELECT ... WHERE sent = FALSE` is fine. With two app instances, both see the same unsent rows and both publish them, so every event goes out twice.

The fix is to lock the batch (suggested in a comment in `OutboxRelay.java`, not enabled):

```sql
SELECT id, topic, msg_key, payload FROM outbox
WHERE sent = FALSE ORDER BY id LIMIT 100
FOR UPDATE SKIP LOCKED
```

```
              plain SELECT                         FOR UPDATE SKIP LOCKED
 relay A: rows 1,2,3 ─► send                  relay A: locks 1,2,3 ─► send
 relay B: rows 1,2,3 ─► send  (duplicates)    relay B: skips them, locks 4,5,6 ─► send
```

- `FOR UPDATE` locks the returned rows until the transaction ends. 
- `SKIP LOCKED` makes other relays skip them instead of waiting.
- The transaction must cover the select, the Kafka send and the `UPDATE`. If it commits before the send, the lock is gone.
- Still at-least-once: a relay that crashes after sending but before committing releases the lock, and another relay resends. Consumers must handle duplicates.
- Rows are no longer published in strict order across batches. If per-key order matters, split the work by key.
- Needs Postgres or MySQL 8+. This POC uses H2, so it is not tested here.

## Not covered

Polling adds latency. CDC with Debezium avoids it, see [DEBEZIUM.md](DEBEZIUM.md) for the notes (not built). Sent rows are never cleaned up.
