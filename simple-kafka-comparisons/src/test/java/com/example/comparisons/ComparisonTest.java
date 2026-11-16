package com.example.comparisons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One scenario, three brokers: good message, poison message, good message, then replay. Kafka runs in memory;
 * RabbitMQ and Redis run as real brokers in Docker containers, so this test needs Docker.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 3, topics = { "orders", "orders.DLT" })
@Testcontainers
class ComparisonTest {

  @Container
  static final GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:4").withExposedPorts(5672)
      .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", rabbit::getHost);
    registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired Map<String, Broker> brokers;
  @Autowired Handler handler;

  @Test
  void kafkaRetriesBlockThePartition() throws Exception {
    var events = scenario("kafka");

    // burger is stuck behind the poison message until its 3 attempts are used up
    assertThat(events).containsExactly("kafka-pizza", "kafka-poison", "kafka-poison", "kafka-poison", "kafka-burger");
  }

  @Test
  void rabbitRetriesDoNotBlockTheQueue() throws Exception {
    var events = scenario("rabbit");

    // burger is processed while the poison message waits in orders.wait
    assertThat(events.indexOf("rabbit-burger")).isLessThan(events.lastIndexOf("rabbit-poison"));
  }

  @Test
  void redisRetriesDoNotBlockTheStream() throws Exception {
    var events = scenario("redis");

    // burger is processed while the poison message waits in the pending list
    assertThat(events.indexOf("redis-burger")).isLessThan(events.lastIndexOf("redis-poison"));
  }

  /** The same checks for every broker. Returns the order the attempts ran in, before the replay. */
  private List<String> scenario(String tag) throws Exception {
    var broker = brokers.get(tag);
    var pizza = tag + "-pizza";
    var poison = tag + "-poison";
    var burger = tag + "-burger";

    broker.publish("user-1", pizza);
    broker.publish("user-1", poison);
    broker.publish("user-1", burger);

    // good ones go through, the poison one is tried exactly 3 times and then parked, not lost, not stuck forever
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(handler.processed).contains(pizza, burger).doesNotContain(poison);
      assertThat(handler.attempts(poison)).isEqualTo(Handler.MAX_ATTEMPTS);
      assertThat(broker.parked()).isEqualTo(1);
    });
    var events = handler.events.stream().filter(e -> e.startsWith(tag)).toList();

    // after the "fix", replay moves it back to the main queue and it is processed for real
    assertThat(broker.replay()).isEqualTo(1);
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(handler.processed).contains(poison);
      assertThat(broker.parked()).isZero();
    });
    return events;
  }
}
