package com.example.deliveryguarantees;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IDEMPOTENT CONSUMER. Kafka can deliver a message twice (outbox resend, rebalance, producer retry after a lost ack),
 * so handling a message must be safe to repeat.
 *
 * Trick: in ONE database transaction, insert the message id into processed_messages (a PRIMARY KEY) and do the side effect.
 * A second delivery hits the duplicate key, the transaction rolls back, and the effect happens only once.
 * Marker and effect commit together, so a crash can't leave one without the other.
 */
@Component
public class Inbox {

  static final String MESSAGE_ID = "message-id";

  private static final Logger log = LoggerFactory.getLogger(Inbox.class);

  // kept only so the test can check what happened
  final List<String> duplicates = new CopyOnWriteArrayList<>();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  Inbox(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @KafkaListener(topics = DeliveryGuaranteesApplication.ORDERS, groupId = "inbox")
  void on(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader(MESSAGE_ID);
    if (header == null) {
      // without an id we can't dedupe; failing loudly beats silently double-processing
      throw new IllegalArgumentException("message has no " + MESSAGE_ID + " header");
    }
    String id = new String(header.value(), StandardCharsets.UTF_8);
    try {
      tx.executeWithoutResult(status -> {
        jdbc.update("INSERT INTO processed_messages (message_id) VALUES (?)", id);
        jdbc.update("INSERT INTO fulfilled (payload) VALUES (?)", record.value());
      });
      log.info("FULFILLED id={} value={}", id, record.value());
    } catch (DuplicateKeyException e) {
      log.warn("DUPLICATE skipped id={} value={}", id, record.value());
      duplicates.add(id);
    }
  }
}
