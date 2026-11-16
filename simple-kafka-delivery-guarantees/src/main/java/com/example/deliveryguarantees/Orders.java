package com.example.deliveryguarantees;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ORDERS. Saves an order and announces it on Kafka.
 *
 * The naive version does "INSERT order" then "kafka.send(...)". Two systems, no shared transaction:
 * crash in between and the order exists but nobody hears about it (or the send worked and the insert rolled back).
 * The OUTBOX fix: write the announcement to a table in the SAME database transaction as the order.
 * OutboxPoller delivers it to Kafka afterwards.
 *
 * curl -X POST "localhost:8080/orders?item=pizza"
 */
@RestController
public class Orders {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  Orders(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @PostMapping("/orders")
  String place(@RequestParam String item) {
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(status -> {
      jdbc.update("INSERT INTO orders (id, item) VALUES (?, ?)", id, item);
      jdbc.update("INSERT INTO outbox (topic, msg_key, payload) VALUES (?, ?, ?)", DeliveryGuaranteesApplication.ORDERS, id, item);
      // simulates a crash between the writes: the rollback takes the order AND the outbox row with it
      if ("explode".equals(item)) {
        throw new IllegalStateException("crashed after both inserts");
      }
    });
    return "order " + id + "\n";
  }
}
