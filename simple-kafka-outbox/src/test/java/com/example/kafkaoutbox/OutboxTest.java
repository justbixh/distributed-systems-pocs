package com.example.kafkaoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = KafkaOutboxApplication.TOPIC)
class OutboxTest {

  @Autowired OrderService orders;
  @Autowired Consumer consumer;
  @Autowired JdbcClient db;

  @Test
  void orderIsSavedThenPublishedAndMarkedSent() {
    var id = orders.create("pizza");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(consumer.received).contains("pizza");
      assertThat(db.sql("SELECT COUNT(*) FROM outbox WHERE msg_key = ? AND sent = TRUE").param(id).query(Integer.class).single()).isEqualTo(1);
    });
  }

  @Test
  void failedTransactionLeavesNoOrderAndNoEvent() throws Exception {
    assertThatThrownBy(() -> orders.create("boom")).isInstanceOf(IllegalStateException.class);

    Thread.sleep(2000); // give the relay several ticks to (wrongly) publish it
    assertThat(db.sql("SELECT COUNT(*) FROM orders WHERE item = 'boom'").query(Integer.class).single()).isZero();
    assertThat(db.sql("SELECT COUNT(*) FROM outbox WHERE payload = 'boom'").query(Integer.class).single()).isZero();
    assertThat(consumer.received).doesNotContain("boom");
  }
}
