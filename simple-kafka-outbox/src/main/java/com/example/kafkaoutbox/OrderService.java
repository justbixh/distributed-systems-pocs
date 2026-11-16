package com.example.kafkaoutbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Service
public class OrderService {

  private final JdbcClient db;

  OrderService(JdbcClient db) {
    this.db = db;
  }

  // The order and its event are written in ONE database transaction: both exist or neither does.
  // Nothing here talks to Kafka, so a Kafka outage cannot lose the event or leave the DB out of sync.
  @PostMapping("/orders")
  @Transactional
  public String create(@RequestParam String item) {
    var id = UUID.randomUUID().toString();
    db.sql("INSERT INTO orders (id, item) VALUES (?, ?)").params(id, item).update();
    db.sql("INSERT INTO outbox (topic, msg_key, payload) VALUES (?, ?, ?)")
        .params(KafkaOutboxApplication.TOPIC, id, item).update();
    if (item.equals("boom")) throw new IllegalStateException("simulated failure after both inserts");
    return id;
  }
}
