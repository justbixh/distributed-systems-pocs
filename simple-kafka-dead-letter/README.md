# simple-kafka-dead-letter

## Problem

What happens to a Kafka message when your code fails to process it? Is it lost, does it block everything behind it, or does it go somewhere you can look at it later?

Follow-up to [simple-kafka-pubsub](../simple-kafka-pubsub). Same Kafka setup, one new idea: the **dead-letter topic**.

## The whole idea in 30 seconds

A consumer reads a message and its code throws. Kafka doesn't care, because it only tracks the bookmark (offset). What happens next is up to your listener's **error handler**:

- **Spring's default:** retry the message a few times, then log it and **skip it**. The message is lost.
- **With a dead-letter topic (this project):** retry 2 more times, 500 ms apart, then copy the message to `orders.DLT` and move on. Nothing is lost, nothing is stuck.

```
                                          ┌─ ok ───────────────────────────► done
POST /publish ──► topic "orders" ──► Worker
                                          └─ throws ─► retry, retry ─► topic "orders.DLT" ──► dlt-viewer (a human looks)
```

## Code tour

- `KafkaDeadLetterApplication.java` declares `orders` and `orders.DLT` (3 partitions each) and the error handler: 2 retries, then publish to the dead-letter topic.
- `Worker.java` has two listeners. `work` throws when the value is `poison`, and `deadLetter` logs whatever lands in `orders.DLT`.
- `Publisher.java` is unchanged from the pubsub project.

The dead-letter topic needs **at least as many partitions as `orders`**, because a failed record is sent to the same partition number.

## How to run

```bash
docker compose up -d          # start Kafka on localhost:9092
./mvnw spring-boot:run        # start the app on :8080
./mvnw test                   # automated check (in-memory Kafka, no Docker)
```

## Experiment: send a poison message

```bash
for v in pizza poison burger; do
  curl -s -X POST "localhost:8080/publish?key=alice" -H 'Content-Type: text/plain' -d "$v"
done
```

Log, in order:

```
PROCESSING partition=2 offset=0 value=pizza
PROCESSING partition=2 offset=1 value=poison     <- attempt 1, fails
PROCESSING partition=2 offset=1 value=poison     <- retry 1
PROCESSING partition=2 offset=1 value=poison     <- retry 2
PROCESSING partition=2 offset=2 value=burger     <- moved on, not blocked
DEAD LETTER partition=2 offset=0 value=poison    <- parked for a human
```

Things to notice:
- `burger` is behind `poison` on the same partition, so it waits about a second for the retries and is then processed. The poison message delays the partition but doesn't block it forever.
- Look at the dead-letter topic directly:
  ```bash
  docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic orders.DLT --from-beginning
  ```
- Delete the `errorHandler` bean and repeat. Spring retries 10 times with no pause and then skips `poison` silently, so there is nothing in any dead-letter topic.

## More failure modes

- **Poison pill (undeserializable payload).** `quantities` expects Integers. `curl -X POST "localhost:8080/publish?topic=quantities" -d "abc"` fails inside the deserializer, so no `@KafkaListener` code runs. Without `ErrorHandlingDeserializer` the consumer loops on that offset forever. With it, the record goes straight to `orders.DLT` with no retries, because the same bytes can never succeed.
- **Replay.** `curl -X POST localhost:8080/replay` puts every parked dead letter back on `orders` and marks it "fixed" (simulating a deployed bug fix) so it now processes. The parked list is in memory, so a restart loses it.
- **Retry topics.** `curl -X POST "localhost:8080/publish?topic=shipments" -d poison` goes through `shipments-retry-0`, `shipments-retry-1` and then `shipments-dlt`, with a growing delay. Unlike the blocking retries on `orders`, the partition keeps flowing while the message waits.

## Takeaways

- A failing message must not block the partition forever, and must not vanish. A dead-letter topic does both.
- Retries are for temporary failures (a database blip). A poison message fails every time, so it goes to the dead-letter topic.
- Someone has to **watch** the dead-letter topic: alert on it, fix the cause, then republish.
- Not covered yet: `acks=all` and idempotent producers (duplicates), manual offset commits, 3 brokers with replication.
