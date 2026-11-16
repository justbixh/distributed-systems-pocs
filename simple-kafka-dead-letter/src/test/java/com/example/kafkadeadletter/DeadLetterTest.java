package com.example.kafkadeadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

// tests share one Spring context, so each uses its own values and checks with contains()
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = { KafkaDeadLetterApplication.TOPIC, KafkaDeadLetterApplication.DLT,
    KafkaDeadLetterApplication.QUANTITIES, KafkaDeadLetterApplication.SHIPMENTS })
class DeadLetterTest {

  static final String ORDERS = KafkaDeadLetterApplication.TOPIC;

  @Autowired Publisher publisher;
  @Autowired Replay replay;
  @Autowired Worker worker;
  @Autowired KafkaTemplate<String, String> kafka;

  @Test
  void poisonMessageGoesToDeadLetterTopicAndGoodOnesStillWork() throws Exception {
    publisher.publish("user-1", ORDERS, "pizza");
    publisher.publish("user-1", ORDERS, "poison");
    publisher.publish("user-1", ORDERS, "burger");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(worker.processed).contains("pizza", "burger").doesNotContain("poison");
      assertThat(worker.deadLettered).contains("poison");
    });
  }

  @Test
  void undeserializableMessageGoesToDeadLetterTopicWithoutRetries() throws Exception {
    kafka.send(KafkaDeadLetterApplication.QUANTITIES, "user-2", "not-a-number").get();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(worker.deadLettered).contains("not-a-number");
      assertThat(worker.quantities).isEmpty();
    });
  }

  @Test
  void replayedDeadLetterIsProcessedOnceFixed() throws Exception {
    publisher.publish("user-3", ORDERS, "poison-replay");
    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(worker.deadLettered).contains("poison-replay"));

    replay.replay();

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(worker.processed).contains("poison-replay"));
  }

  @Test
  void failingShipmentIsRetriedThroughRetryTopicsThenGivenUp() throws Exception {
    kafka.send(KafkaDeadLetterApplication.SHIPMENTS, "user-4", "poison").get();

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(worker.shipmentAttempts).containsExactly("poison", "poison", "poison");
      assertThat(worker.shipmentsGivenUp).containsExactly("poison");
    });
  }
}
