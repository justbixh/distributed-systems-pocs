package com.example.deliveryguarantees;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OUTBOX POLLER. Every 200 ms: take unsent outbox rows, send them to Kafka, mark them sent.
 *
 * Crash after the send but before the "mark sent" and the row is sent AGAIN on restart.
 * So the outbox gives AT-LEAST-ONCE delivery: never lost, sometimes duplicated. The consumer (Inbox) removes the duplicates.
 * The outbox row id travels as the "message-id" header so the consumer can recognise a repeat.
 *
 * shortcut: polling one instance; two pollers would send the same row twice, and polling adds latency.
 * Upgrade: Debezium reading the database log (CDC), or SELECT ... FOR UPDATE SKIP LOCKED for several pollers.
 */
@Component
public class OutboxPoller {

  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;

  OutboxPoller(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
    this.jdbc = jdbc;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelay = 200)
  void publishPending() throws Exception {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id, topic, msg_key, payload FROM outbox WHERE published = FALSE ORDER BY id LIMIT 50");
    for (var row : rows) {
      var record = new ProducerRecord<String, String>((String) row.get("TOPIC"), (String) row.get("MSG_KEY"), (String) row.get("PAYLOAD"));
      record.headers().add(Inbox.MESSAGE_ID, row.get("ID").toString().getBytes(StandardCharsets.UTF_8));
      kafka.send(record).get(); // wait for the broker's ack before marking the row as sent
      jdbc.update("UPDATE outbox SET published = TRUE WHERE id = ?", row.get("ID"));
      log.info("OUTBOX SENT id={} payload={}", row.get("ID"), row.get("PAYLOAD"));
    }
  }
}
