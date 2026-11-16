# simple-kafka-schema-registry

## Problem

A Kafka message is just bytes. When the producer adds a field to its message, what happens to the consumers that were written before the field existed? And what stops someone from shipping a change that breaks every consumer?

Follow-up to [simple-kafka-dead-letter](../simple-kafka-dead-letter). One new idea: the **Schema Registry**.

## The whole idea in 30 seconds

Instead of putting the whole schema in every message, the producer registers the schema once in the **Schema Registry** (under the subject `orders-value`) and each message carries only a 4-byte **schema id**. The consumer looks the id up, learns how the message was written, and Avro translates it to the schema the consumer was built with.

The registry also guards the door. Every new version is checked against the earlier ones, by default with the **BACKWARD** rule: *a consumer on the new schema must be able to read data written with the old ones.*

```
Publisher ──► registry: "is v2 compatible with v1?" ── yes ─► message = [schema id 2][bytes] ──► topic "orders"
                                                                                                       │
              registry: "is v3 compatible with v2?" ── NO (409) ─► send fails, nothing reaches Kafka   ├──► old consumer (v1)
                                                                                                       └──► new consumer (v2)
```

The three schemas are in `src/main/resources/avro`:

| Schema | Fields | Verdict |
|---|---|---|
| `order-v1` | `id`, `item` | first version |
| `order-v2` | + `note` **with a default** | compatible: old data has no `note`, so the default fills it |
| `order-v3-breaking` | + `priority` **without a default** | rejected: nothing to put there for old data |

## Code tour

- `Schemas.java` loads the three `.avsc` files.
- `Publisher.java` writes an Order as v1, v2 or v3. The Confluent `KafkaAvroSerializer` registers the schema on first use.
- `Reader.java` is two consumers of the same topic: an "old" one with the v1 schema and a "new" one with v2. It consumes raw bytes so it can choose its **reader schema**; the writer schema comes from the registry.
- `SchemaEvolutionTest.java` runs everything with embedded Kafka and an in-memory registry (`mock://`), so no Docker is needed.

## How to run

```bash
docker compose up -d          # Kafka on :9092 and the Schema Registry on :8081
./mvnw spring-boot:run        # start the app on :8080
./mvnw test                   # automated check (embedded Kafka + mock registry, no Docker)
```

The compose file reuses the container name `kafka` from the other POCs. Run `docker compose down` there first to free port 9092.

## Experiment: evolve the schema

```bash
curl -X POST "localhost:8080/publish?version=1&id=1&item=pizza"
curl -X POST "localhost:8080/publish?version=2&id=2&item=burger&note=extra-cheese"
curl -X POST "localhost:8080/publish?version=3&id=3&item=salad"     # fails
```

Log:

```
OLD consumer (v1) got {"id": "1", "item": "pizza"}
NEW consumer (v2) got {"id": "1", "item": "pizza", "note": ""}                   <- default filled in
OLD consumer (v1) got {"id": "2", "item": "burger"}                              <- new field ignored
NEW consumer (v2) got {"id": "2", "item": "burger", "note": "extra-cheese"}
```

The third call returns an error: the registry answers `409 ... READER_FIELD_MISSING_DEFAULT_VALUE` and the serializer refuses to send.

Look at the registry directly:

```bash
curl localhost:8081/subjects                                  # ["orders-value"]
curl localhost:8081/subjects/orders-value/versions            # [1,2]  (v3 never got in)
curl localhost:8081/config                                    # {"compatibilityLevel":"BACKWARD"}
```

Things to try: change the compatibility level (`curl -X PUT -H 'Content-Type: application/vnd.schemaregistry.v1+json' -d '{"compatibility":"NONE"}' localhost:8081/config`) and publish v3 again.

## Takeaways

- **Messages stay small.** The schema lives in the registry, the message carries an id.
- **Add fields with a default, never without.** Removing a field is also safe if it had a default. Renaming or changing a type is breaking.
- **BACKWARD means upgrade consumers first, then producers.** A new consumer can read old data, so consumers are always safe to deploy ahead of producers.
- **The check happens at publish time**, when the producer registers the schema, not when a consumer crashes at 3 a.m.

## Limits of this POC

- The mock registry used in the tests answers the compatibility API but, unlike the real registry, does not reject `register()` calls. The test therefore asks `testCompatibility` instead of expecting the send to fail. The real 409 was checked by hand against `cp-schema-registry` over `curl`; the app itself was not run against it.
- `auto.register.schemas` is left on (the default) for convenience. In production you would register schemas in CI and turn it off.
- Schemas are loaded from `.avsc` files as generic records. No generated classes.
