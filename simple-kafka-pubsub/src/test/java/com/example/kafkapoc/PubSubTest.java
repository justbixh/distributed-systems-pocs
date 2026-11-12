package com.example.kafkapoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

// Starts a real Kafka broker in memory and points the app at it.
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = KafkaPocApplication.TOPIC)
class PubSubTest {

  @Autowired Publisher publisher;
  @Autowired Subscribers subscribers;

  @Test
  void everyGroupGetsEveryMessage() throws Exception {
    publisher.publish("user-1", "pizza");
    publisher.publish("user-2", "burger");
    publisher.publish(null, "salad");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(subscribers.emailReceived).containsExactlyInAnyOrder("pizza", "burger", "salad");
      assertThat(subscribers.analyticsReceived).containsExactlyInAnyOrder("pizza", "burger", "salad");
    });
  }
}
