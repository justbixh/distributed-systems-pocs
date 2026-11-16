package com.example.deliveryguarantees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

// tests share one Spring context and one database, so each uses its own values
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = { DeliveryGuaranteesApplication.ORDERS, DeliveryGuaranteesApplication.PAYMENTS,
    DeliveryGuaranteesApplication.RECEIPTS })
class DeliveryGuaranteesTest {

  @Autowired Orders orders;
  @Autowired Inbox inbox;
  @Autowired Receipts receipts;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void producerIsIdempotentAndWaitsForAllReplicas() {
    var config = kafka.getProducerFactory().getConfigurationProperties();
    assertThat(config.get("acks").toString()).isEqualTo("all");
    assertThat(config.get("enable.idempotence").toString()).isEqualTo("true");
  }

  @Test
  void sameMessageDeliveredTwiceIsProcessedOnce() throws Exception {
    for (int i = 0; i < 2; i++) {
      var record = new ProducerRecord<String, String>(DeliveryGuaranteesApplication.ORDERS, "k", "duplicated-sushi");
      record.headers().add(Inbox.MESSAGE_ID, "dup-1".getBytes(StandardCharsets.UTF_8));
      kafka.send(record).get();
    }

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(inbox.duplicates).contains("dup-1"));
    assertThat(count("SELECT COUNT(*) FROM fulfilled WHERE payload = ?", "duplicated-sushi")).isEqualTo(1);
  }

  @Test
  void orderReachesTheConsumerThroughTheOutbox() {
    orders.place("pizza");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(
        () -> assertThat(count("SELECT COUNT(*) FROM fulfilled WHERE payload = ?", "pizza")).isEqualTo(1));
  }

  @Test
  void crashBetweenOrderAndOutboxRowLeavesNothingBehind() {
    assertThatThrownBy(() -> orders.place("explode")).isInstanceOf(IllegalStateException.class);

    assertThat(count("SELECT COUNT(*) FROM orders WHERE item = ?", "explode")).isZero();
    assertThat(count("SELECT COUNT(*) FROM outbox WHERE payload = ?", "explode")).isZero();
  }

  @Test
  void outboxResendAfterACrashIsDeduplicatedByTheConsumer() {
    orders.place("burger");
    await().atMost(Duration.ofSeconds(20)).untilAsserted(
        () -> assertThat(count("SELECT COUNT(*) FROM fulfilled WHERE payload = ?", "burger")).isEqualTo(1));

    // a crash after the send but before "mark as sent" looks exactly like this: the row is sent again
    jdbc.update("UPDATE outbox SET published = FALSE WHERE payload = ?", "burger");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(inbox.duplicates).isNotEmpty());
    assertThat(count("SELECT COUNT(*) FROM fulfilled WHERE payload = ?", "burger")).isEqualTo(1);
  }

  @Test
  void receiptFromAnAbortedTransactionIsNeverVisibleAndTheRetryProducesExactlyOne() throws Exception {
    kafka.send(DeliveryGuaranteesApplication.PAYMENTS, "user-1", "flaky-1").get();

    // 2 attempts (the first aborted after sending), yet exactly one receipt, and it stays one
    await().atMost(Duration.ofSeconds(30)).during(Duration.ofSeconds(2)).untilAsserted(() -> {
      assertThat(receipts.attempts).containsExactly("flaky-1", "flaky-1");
      assertThat(receipts.visibleReceipts).containsExactly("receipt:flaky-1");
    });
  }
}
