# Note: Debezium instead of the polling outbox

Not implemented. `OutboxPoller.java` and the README "Not covered" list only name it as the upgrade path.

## Today (polling)
1. `Orders` writes the order and an `outbox` row in one DB transaction.
2. `OutboxPoller` runs every 200 ms: selects `published = FALSE` rows, sends to Kafka, waits for the ack, sets `published = TRUE`.
3. Crash after send but before the update: the row is sent again. Delivery is at-least-once.
4. `Inbox` drops duplicates using the `message-id` header (the outbox row id).

## With Debezium (CDC)
1. `Orders` is unchanged: same transaction, same outbox row.
2. Debezium (Kafka Connect) tails the MySQL binlog and publishes each new `outbox` row to Kafka. No polling, no extra queries.
3. It stores its binlog offset instead of a `published` flag. A crash can still resend, so it is still at-least-once and `Inbox` dedup stays.

```
Orders -> DB(outbox) -> binlog -> Kafka Connect + Debezium EventRouter -> Kafka -> Inbox
```

## Full flow, side by side
```
NOW (polling)
┌─────────┐ 1 tx: order + outbox row ┌───────┐
│ Orders  │─────────────────────────▶│  DB   │
└─────────┘                          └───┬───┘
                    2 SELECT ... WHERE published=FALSE  (every 200ms)
                                         ▼
                                  ┌──────────────┐  3 send, wait ack   ┌───────┐
                                  │ OutboxPoller │────────────────────▶│ Kafka │
                                  └──────┬───────┘                     └───┬───┘
                    4 UPDATE published=TRUE                               │
                                                                    ┌─────▼─────┐
                                                                    │  Inbox    │ dedup by message-id
                                                                    └───────────┘

WITH DEBEZIUM (CDC)
┌─────────┐ 1 tx: order + outbox row ┌───────┐
│ Orders  │─────────────────────────▶│  DB   │──writes──▶ binlog (transaction log)
└─────────┘                          └───────┘               │
                                                  2 tails the log, no queries
                                                             ▼
                                              ┌────────────────────────────┐
                                              │ Kafka Connect + Debezium   │
                                              │ (outbox event router)      │
                                              └─────────────┬──────────────┘
                                                  3 publish │ (saves binlog offset)
                                                            ▼
                                                        ┌───────┐
                                                        │ Kafka │──▶ Inbox (still dedups)
                                                        └───────┘
```

## What changes
- Delete `OutboxPoller`. The `published` column is no longer needed.
- `Inbox` unchanged, if the event id reaches it as the `message-id` header
  (EventRouter: `table.fields.additional.placement=id:header:message-id`, untested).
- Add: a Kafka Connect container with the Debezium plugin, binlog enabled on MySQL, and the connector JSON posted to Connect's REST API.
- Only works with the `mysql` profile. H2 has no binlog.

## Connector config (untested sketch)
```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql",
    "database.user": "root",
    "database.password": "password",
    "database.server.id": "1",
    "topic.prefix": "delivery",
    "table.include.list": "delivery.outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "msg_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "topic"
  }
}
```
