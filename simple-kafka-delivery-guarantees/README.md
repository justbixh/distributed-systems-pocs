# simple-kafka-delivery-guarantees

## Problem

Can a message be lost? Delivered twice? What if the app crashes right after saving to the database but before sending to Kafka?

Follow-up to [simple-kafka-dead-letter](../simple-kafka-dead-letter), which was about messages that **fail**. This one is about messages that get **lost or duplicated**.

## The whole idea in 30 seconds

Kafka can't give you "exactly once" for free across a database and a topic. You combine small guarantees:

| Guarantee | Where | How |
|---|---|---|
| Producer retries don't create duplicates | app -> Kafka | `acks=all` + `enable.idempotence=true` (`application.yml`) |
| Never lose an announcement | database -> Kafka | **transactional outbox**: write the message to a table in the same DB transaction as the data, a poller sends it later |
| Duplicates are harmless | Kafka -> database | **idempotent consumer**: record the message id and do the effect in one DB transaction |
| Kafka -> Kafka is atomic | topic -> topic | **Kafka transactions**: output + consumed offset commit together |

```
POST /orders ─► DB transaction { orders row + outbox row } ─► OutboxPoller ─► topic "orders" ─► Inbox
                (both or neither)                (at-least-once: may resend)      DB transaction { processed_messages row + fulfilled row }
                                                                                  2nd delivery = duplicate key = skipped

topic "payments" ─► Receipts (Kafka transaction: send receipt + commit offset) ─► topic "receipts" ─► read_committed viewer
```

At-least-once delivery (outbox) + idempotent handling (inbox) together behave like exactly-once **for the database effect**.

## Code tour

- `Orders.java`: `POST /orders` saves the order and an outbox row in one transaction. The item `explode` throws in the middle, to show both rows roll back.
- `OutboxPoller.java`: every 200 ms sends unsent rows to Kafka, then marks them sent. The outbox row id goes along as the `message-id` header.
- `Inbox.java`: the idempotent consumer. `processed_messages.message_id` is a PRIMARY KEY, so a repeat fails the insert and the whole transaction rolls back.
- `Receipts.java` + `ExactlyOnceConfig.java`: consume `payments`, produce `receipts`, inside a Kafka transaction. A `flaky-*` payment fails once after sending the receipt.
- `schema.sql`: the four tables.
- `application.yml`: `acks=all` and `enable.idempotence=true`. Both are already the default in Kafka 3+; set explicitly so the guarantee is visible.

## How to run

```bash
docker compose up -d kafka    # Kafka on localhost:9092
./mvnw spring-boot:run        # app on :8080, in-memory H2 database
./mvnw test                   # automated checks (in-memory Kafka + H2, no Docker)
```

With MySQL instead of H2: `docker compose up -d` then `./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql`.

## Experiments

**1. Outbox, happy path**
```bash
curl -s -X POST "localhost:8080/orders?item=pizza"
```
```
OUTBOX SENT id=1 payload=pizza
FULFILLED id=1 value=pizza
```

**2. Crash between the two writes**
```bash
curl -s -X POST "localhost:8080/orders?item=explode"     # 500 error
```
No `OUTBOX SENT`, and no `orders` row. The order and its announcement are never half-saved.

**3. Duplicate delivery.** Stop the app right after `OUTBOX SENT` is logged, or set `published = FALSE` on a sent row in the H2 console / MySQL:
```
DUPLICATE skipped id=1 value=pizza
```
The row is sent again (that's at-least-once), the consumer skips it, and `fulfilled` still has one row.

**4. Kafka transaction**
```bash
docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payments
> flaky-1
```
```
PAYMENT value=flaky-1     <- sends the receipt, then throws: transaction aborted
PAYMENT value=flaky-1     <- retried, succeeds
RECEIPT value=receipt:flaky-1    <- only ONE receipt is ever visible
```
The aborted receipt is on the topic but hidden from `read_committed` consumers. Remove `isolation.level=read_committed` from `Receipts.receipt` and you'll see two.

## Takeaways

- **The dual write is the bug.** "Save, then send" can never be made safe with two independent systems. The outbox turns it into one write plus a retryable copy.
- **Outbox = at-least-once. Inbox = dedupe.** You need both. Neither alone gives you "once".
- **Idempotent producer only protects against the producer's own retries**, within one producer session. It does nothing for duplicates created upstream (outbox resend, user double-click).
- **Kafka transactions only cover Kafka.** A DB write inside that listener is not rolled back. That is what the Inbox pattern is for.
- **Consumers must opt in** (`isolation.level=read_committed`) to be protected from aborted transactions.

## Not covered

- Debezium (change data capture) instead of polling; see the `shortcut:` note in `OutboxPoller.java`.
- Several poller instances (`FOR UPDATE SKIP LOCKED`).
- Cleaning up old `outbox` and `processed_messages` rows.
- Ordering of outbox rows across partitions.
