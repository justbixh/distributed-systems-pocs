# simple-kafka-pubsub

## Problem

How does publish/subscribe actually work in Kafka? Who gets a message, where is it stored, and how do several services read the same events without stepping on each other?

## The whole idea in 30 seconds

Kafka is an **append-only log** (think of a notebook you can only add lines to). Publishers write lines at the end. Subscribers each keep a **bookmark** saying how far they've read. Kafka never deletes a line just because someone read it. Every reader moves its own bookmark.

That's all pub-sub is here: one writer, many independent bookmarks.

```
                         topic "orders" (3 partitions)
                     ┌──────────────────────────────────┐
POST /publish ──►    │ partition 0: [m0][m1][m2] ...    │ ──► email-service      (1 consumer, reads all 3 partitions)
  Publisher          │ partition 1: [m0][m1] ...        │
  (KafkaTemplate)    │ partition 2: [m0][m1][m2][m3]... │ ──► analytics-service  (2 consumers, split the partitions)
                     └──────────────────────────────────┘          ├─ thread #0-0 → partitions 0, 1
                                                                   └─ thread #0-1 → partition 2
```

## Vocabulary → where it is in the code

| Word | Plain meaning | In this project |
|---|---|---|
| **Broker** | The Kafka server that stores messages | `docker-compose.yml` |
| **Topic** | A named stream of messages, like a table name | `orders`, created in `KafkaPocApplication.ordersTopic()` |
| **Partition** | A topic is split into N independent logs. This is Kafka's unit of parallelism and ordering | 3 partitions |
| **Offset** | A message's position in its partition (0, 1, 2…). It works like a line number | printed in every log line |
| **Key** | Optional label on a message. Same key → same partition → kept in order | `?key=alice` |
| **Producer / Publisher** | Writes messages. It doesn't know who reads them | `Publisher.java` |
| **Consumer / Subscriber** | Reads messages | `Subscribers.java` |
| **Consumer group** | A team of consumers that share the work. **Each group gets every message once** | `groupId = "email-service"` / `"analytics-service"` |
| **Committed offset** | The group's bookmark: "we've processed up to here". Kafka stores it for the group | Spring commits it for you after your method returns |
| **Lag** | How far behind a group is: log end offset minus committed offset | shown by `kafka-consumer-groups.sh` |

### The two rules that explain everything

1. **Different groups → everyone gets everything.** `email-service` and `analytics-service` each receive every message. This is the *pub-sub* (fan-out) part.
2. **Same group → the work is split.** Each partition goes to exactly one consumer in the group. The 2 analytics threads split the 3 partitions (2 + 1), so each message is handled once *per group*. This is the *queue* part. One consequence: **more consumers than partitions = idle consumers.**

## Code tour (3 files)

- `KafkaPocApplication.java` declares the `orders` topic with 3 partitions.
- `Publisher.java` has `POST /publish?key=…`. It calls `kafkaTemplate.send(topic, key, value)`, waits for the broker's acknowledgement, and returns **which partition and offset** the message was stored at.
- `Subscribers.java` has two `@KafkaListener` methods with different `groupId`s. Each one logs `group / thread / partition / offset / key / value`.

## How to run

```bash
docker compose up -d          # start Kafka on localhost:9092
./mvnw spring-boot:run        # start the app on :8080 (keep this terminal open to watch the logs)
```

Run the automated test (it uses an in-memory Kafka, no Docker needed):

```bash
./mvnw test
```

The `ERROR ... checkpoint file` lines at the end of the test output are just the in-memory broker shutting down. The test still passes.

## Experiments, in order

### 1. Watch the startup: who got which partitions?

In the app log, look for:

```
email-service: partitions assigned: [orders-0, orders-1, orders-2]
analytics-service: partitions assigned: [orders-0, orders-1]
analytics-service: partitions assigned: [orders-2]
```

Rule 2 is visible here: one email consumer takes all 3 partitions, and the two analytics consumers split them.

### 2. Publish one message, see it fan out (rule 1)

```bash
curl -X POST "localhost:8080/publish?key=alice" -H 'Content-Type: text/plain' -d "pizza"
# stored at topic=orders partition=0 offset=0
```

Log:

```
PUBLISHED  key=alice value=pizza -> topic=orders partition=0 offset=0
CONSUMED   group=email-service     ... partition=0 offset=0 key=alice value=pizza
CONSUMED   group=analytics-service ... partition=0 offset=0 key=alice value=pizza
```

You publish once and it's consumed twice, once per group. The publisher never mentioned either subscriber.

### 3. Keys decide the partition

With 3 partitions, these keys land on different partitions (Kafka hashes the key with murmur2, then takes it mod 3):

| key | partition |
|---|---|
| `alice` | 0 |
| `dave` | 1 |
| `carol` | 2 |

```bash
for k in alice dave carol alice alice; do
  curl -s -X POST "localhost:8080/publish?key=$k" -H 'Content-Type: text/plain' -d "order-$k"
done
```

What to look for:
- All `alice` messages land on partition 0 with offsets 1, 2, 3… in order. **Ordering is only guaranteed within a partition**, which is why you key by "the thing that needs ordering" (user ID, order ID).
- In `analytics-service`, two different thread names show up: one owns partitions 0 and 1, the other owns partition 2 (match them against the `partitions assigned` lines from step 1, since the split can differ between runs). One group, split across two workers.
- Similar-looking keys don't spread out. `user-1`, `user-2` and `user-3` all happen to hash to partition 2.
- No key? A few messages sent together all go to one partition (the producer sticks to one until its batch fills). Use keys to spread them.

### 4. Messages survive while subscribers are down

Kafka keeps messages after they're read (7 days by default), so a subscriber that was offline catches up.

1. Stop the app (Ctrl+C).
2. Publish straight to Kafka with the CLI producer. Type a few lines, then press Ctrl+C:
   ```bash
   docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic orders
   ```
   No key, so they all land on one partition. Add `--property parse.key=true --property key.separator=:` and type `alice:a` to use keys.
3. Check the lag. Both groups are behind by however many lines you typed:
   ```bash
   docker exec -it kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
   ```
4. Start the app again. Both groups consume the missed messages, starting from their saved bookmark. Run step 3 again and `LAG` is back to 0.

### 5. Bookmarks are per group: replay from the start

A group's offset is just a number, so you can move it back. With the app **stopped**:

```bash
docker exec -it kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group analytics-service --topic orders --reset-offsets --to-earliest --execute
```

Start the app. `analytics-service` re-reads **every** message ever sent, while `email-service` reads nothing new. That's replay, and it's the big difference from a classic message queue (RabbitMQ deletes a message once it's acknowledged).

### 6. A brand-new subscriber, no code changes

```bash
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic orders \
  --group new-service --from-beginning --property print.partition=true --property print.key=true
```

A new group shows up, reads the full history, and the publisher and existing subscribers are unaffected. This is why event systems scale organisationally: adding a consumer never touches the producer.

### 7. More consumers than partitions

Change `concurrency = "2"` to `"4"` in `Subscribers.java` and restart. The log shows one analytics consumer with `partitions assigned: []`. 3 partitions can feed at most 3 consumers in one group.

### 8. Scaling in prod

Consumers in a group = `instances × concurrency`. Only as many as there are partitions do any work, the rest sit idle.

| Setup | Consumers in `analytics-service` | Partition split |
|---|---|---|
| 1 instance, concurrency 2 (this POC) | 2 | 2 + 1 |
| 2 instances, concurrency 1 | 2 | 2 + 1, across machines |
| 2 instances, concurrency 2 | 4 | 1 + 1 + 1, **one idle** |
| 3 instances, concurrency 1 | 3 | 1 each |

Prod usually means each service is its own deployable, scaled by replicas (this also gives fault tolerance: a dead pod's partitions move to the survivors). `concurrency` is only a per-pod tuning knob.

Try it: start a second copy and watch the partitions rebalance in both logs.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

No restart needed to scale: new instances join and partitions rebalance on their own. You can also add partitions while everything is running. Clients pick them up on the next metadata refresh (`metadata.max.age.ms`, 5 min by default):

```bash
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --alter --topic orders --partitions 6
```

Only changing `concurrency` needs a restart. Partitions can only be added, never removed, and adding them changes which partition a key goes to.

## Results (from a real run)

Docker Kafka, 4 messages keyed `user-1, user-1, user-2, user-3`:
- Every message was consumed by **both** groups within ~1 ms of publishing.
- All 4 keys hashed to partition 2, so only analytics thread `#0-1` did any work and `#0-0` (partitions 0, 1) sat idle. That's a small taste of a **hot partition**.
- `kafka-consumer-groups.sh` showed both groups at offset 4 on partition 2 with `LAG 0`.

## Takeaways

- Pub-sub in Kafka = **one log + one bookmark per consumer group**. A topic doesn't "send to" subscribers. Subscribers pull and track their own position.
- `groupId` is the switch: different groups means broadcast, same group means split the work.
- Partitions give you both parallelism and ordering, and the key decides the partition. Pick keys with many distinct, evenly spread values.
- Not covered here (good next steps): JSON payloads, error handling and dead-letter topics, `acks`/idempotent producers, manual offset commits, and running 3 brokers to see replication.
