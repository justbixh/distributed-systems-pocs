package com.example.kafkaoutbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Polls the outbox and publishes unsent rows. At-least-once: if the app dies between the Kafka send and the
// UPDATE, the row is sent again on restart, so consumers must tolerate duplicates.
// shortcut: polling and one relay instance, with several you'd need FOR UPDATE SKIP LOCKED (or CDC, e.g. Debezium).
@Component
public class OutboxRelay {

  private final JdbcClient db;
  private final KafkaTemplate<String, String> kafka;

  OutboxRelay(JdbcClient db, KafkaTemplate<String, String> kafka) {
    this.db = db;
    this.kafka = kafka;
  }

  record Row(long id, String topic, String msgKey, String payload) {}

  // To run several relay instances (needs Postgres or MySQL 8+), make relay() @Transactional and lock the batch:
  //   SELECT id, topic, msg_key, payload FROM outbox WHERE sent = FALSE ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
  // Other relays skip the locked rows and take the next ones. The transaction must span select, send and UPDATE,
  // or the lock is released early. Still at-least-once, and order across batches is no longer guaranteed.
  @Scheduled(fixedDelay = 500)
  void relay() throws Exception {
    var rows = db.sql("SELECT id, topic, msg_key, payload FROM outbox WHERE sent = FALSE ORDER BY id")
        .query(Row.class).list();
    for (var r : rows) {
      kafka.send(r.topic(), r.msgKey(), r.payload()).get(); // throws if Kafka is down: row stays unsent, retried next tick
      db.sql("UPDATE outbox SET sent = TRUE WHERE id = ?").param(r.id()).update();
    }
  }
}
