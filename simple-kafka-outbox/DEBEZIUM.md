# Debezium version of the outbox (read-only notes, not built)

Replaces the polling `OutboxRelay` with Debezium, which tails the database's transaction log and publishes each new outbox row to Kafka. Nothing here has been run; config details are from memory, so check them against the Debezium docs before use.

```
POST /orders ─► [ one DB transaction: INSERT orders + INSERT outbox ]
                                     │
                          DB write-ahead log (WAL / binlog)
                                     │  Debezium tails the log
                                     ▼
                       Kafka Connect + Debezium connector
                                     │  outbox event router
                                     ▼
                              topic "orders" ─► Consumer
```

## Polling relay vs Debezium

| | Polling relay (this POC) | Debezium |
|---|---|---|
| Latency | up to one poll interval (500 ms) | milliseconds |
| DB load | repeated `SELECT` | none, reads the log |
| Several app instances | needs `FOR UPDATE SKIP LOCKED` | one log reader, nothing to coordinate |
| Ordering | strict with one relay | commit order |
| "Sent" tracking | `sent` column | Debezium's log position, no column |
| Cleanup | rows pile up | delete right after insert, the insert is already in the log |
| Delivery | at-least-once | at-least-once (duplicates possible after a connector restart) |
| Extra infrastructure | none | Kafka Connect + connector, DB with logical replication |
| Code | ~30 lines of Java | none, but config and ops |

Pick polling for small scale and one instance. Pick `SKIP LOCKED` for several instances without Connect. Pick Debezium for high volume, low latency, or when Connect already runs.

## What it would take

New files
- `register-connector.json`: connector config (Postgres host and credentials, the `outbox` table, the `EventRouter` transform mapping columns to topic, key and payload).
- `register-connector.sh`: `curl -X POST` of that JSON to `localhost:8083/connectors`.

Changed files
- `docker-compose.yml`: add `postgres` (started with `wal_level=logical`) and a Debezium `connect` container. Kafka stays.
- `pom.xml`: swap `h2` for `org.postgresql:postgresql`.
- `application.yml`: Postgres datasource.
- `schema.sql`: Postgres syntax (`BIGSERIAL`), no `sent` column.
- `OrderService.java`: keeps inserting into `outbox`, probably deletes the row right after.

Deleted
- `OutboxRelay.java`.

Tests: the embedded-Kafka test can't cover this because it needs real Postgres and Connect. Use Testcontainers or verify by hand with `docker compose up` and `curl`.

## Connector config sketch (unverified)

```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "postgres",
    "database.password": "postgres",
    "database.dbname": "outbox",
    "topic.prefix": "outbox",
    "table.include.list": "public.outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "msg_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "topic"
  }
}
```

The `EventRouter` expects specific column names by default (`aggregatetype`, `aggregateid`, `payload`, ...), so either rename the columns in `schema.sql` or keep the overrides above.
