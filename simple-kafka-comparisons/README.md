# simple-kafka-comparisons

## Problem

Every broker says "we handle failures", but they mean different things. What do you actually get when a message fails on **Kafka**, **RabbitMQ** and **Redis Streams**, and what do you have to build yourself?

Follow-up to [simple-kafka-dead-letter](../simple-kafka-dead-letter). Same scenario, three brokers, one shared business class.

## The scenario (identical for all three)

1. Publish `pizza`, `poison`, `burger` with the same key.
2. `poison` always fails. It gets **3 attempts** (1 try + 2 retries, 300 ms apart), then is parked in a dead-letter place.
3. `pizza` and `burger` must still be processed. Nothing is lost.
4. Replay: after the "fix", the parked message goes back on the main queue and is processed.

```
Kafka   orders ──► retry on the consumer thread ──► topic orders.DLT          (log, never deleted)
Rabbit  orders ──► orders.wait (TTL 300 ms) ──► orders ... ──► queue orders.dlq   (queue, deleted when replayed)
Redis   stream orders ──► pending list (PEL) ──► XCLAIM after 300 ms ... ──► stream orders.dead
```

## Code tour

- `Broker.java` is the whole common contract: `publish`, `parked`, `replay`.
- `Handler.java` is the business code all three share. `poison` throws until it is "fixed" by a replay. It also records every attempt, so the test can see the order things ran in.
- `kafka/` has the error handler (retry, then `orders.DLT`). `parked` and `replay` use a consumer group called `replay` on the DLT.
- `rabbit/` has three queues (`orders`, `orders.wait`, `orders.dlq`). The broker moves a rejected message through the wait queue; the listener reads the `x-death` header to count attempts and parks the message itself.
- `redis/` has no built-in retry. A 100 ms poll reads new entries, re-claims failed ones after 300 ms and moves them to `orders.dead` when the delivery counter hits 3.
- `Api.java` exposes the same three calls for each broker.
- `ComparisonTest.java` runs one scenario against all three. Kafka is in memory; RabbitMQ and Redis start in Docker via Testcontainers, so **the tests need Docker**.

## How to run

```bash
docker compose up -d          # Kafka :9092, RabbitMQ :5672 (UI :15672 guest/guest), Redis :6379
./mvnw spring-boot:run        # app on :8080
./mvnw test                   # needs Docker running, starts its own RabbitMQ and Redis containers
```

## Experiment

```bash
for b in kafka rabbit redis; do
  for v in pizza poison burger; do
    curl -s -X POST "localhost:8080/$b/publish?key=alice" -H 'Content-Type: text/plain' -d "$b-$v"
  done
done
curl localhost:8080/kafka/parked        # 1, once the 3 attempts are used up
curl -X POST localhost:8080/kafka/replay
```

Watch the log order. This is also what `ComparisonTest` asserts:

```
kafka   pizza, poison, poison, poison, burger     <- burger waits for the retries (blocking)
rabbit  pizza, poison, burger, poison, poison     <- burger overtakes the waiting poison (non-blocking)
redis   pizza, poison, burger, poison, poison     <- same
```

## Comparison

| | Kafka | RabbitMQ | Redis Streams |
|---|---|---|---|
| Built-in retry / DLQ | None. Spring's error handler does both, in your process | Dead-letter exchange and TTL are built in, but the loop is built from queues | None. You get the pending list and a delivery counter, you write the loop |
| Retry style | **Blocking**: the partition waits (or use retry topics, see the sibling repo) | **Non-blocking**: failed message waits in a TTL queue | **Non-blocking**: stays pending while new ones are read |
| Order after a failure | Kept per partition | Lost: a retried message comes back behind newer ones | Lost, same reason |
| Who tracks the retry count | Your process (in memory, forgotten on restart) | The broker writes `x-death`, you read it and decide | Redis keeps the delivery counter, you decide |
| Dead-letter place | A topic: a log you can re-read | A queue: consumed and gone | A stream: a log, you delete entries yourself |
| Replay | Re-read the DLT from an offset (the messages stay) | Drain the DLQ back into the main queue | Copy entries back, delete from the dead stream |
| Retention | Time/size based, replay old data any time | Gone once acked | Stays until you trim it |
| Ops complexity | Highest: cluster, partitions, offsets | Medium: queues, exchanges, policies | Lowest, but you own the most code |

## When to use which

- **Kafka**: you need ordering per key, huge volume, or replay from history. Expect to add retry topics if blocking retries hurt.
- **RabbitMQ**: you want task queues with per-message routing, delays and DLQs as first-class features, and order isn't critical.
- **Redis Streams**: you already run Redis, volume is moderate, and you're fine owning the retry and dead-letter code.

## Takeaways

- Same scenario, same numbers, three very different amounts of code you own: Redis the most, Kafka in the middle, RabbitMQ the least.
- Blocking vs non-blocking retry is the sharpest difference. Kafka protects order and pays with a stuck partition. The other two keep flowing and give up order.
- The dead-letter place is a log in Kafka and Redis, so you can look back at it. In RabbitMQ it is a queue, so once replayed it's gone.
- Not covered: multiple consumers per broker, poison messages that fail to deserialize, Redis `XAUTOCLAIM`, delivery guarantees under broker crashes, performance. This compares failure handling only, not throughput.
