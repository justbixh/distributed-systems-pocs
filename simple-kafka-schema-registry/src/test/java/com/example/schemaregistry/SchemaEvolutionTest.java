package com.example.schemaregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;

// mock:// is an in-memory Schema Registry that lives in the test JVM, so no Docker is needed.
// The tests build on each other (v1 is registered, then v2, then v3 is refused), so they run in order.
@SpringBootTest(properties = { "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.properties.schema.registry.url=mock://schema-evolution-test" })
@EmbeddedKafka(partitions = 3, topics = SchemaRegistryApplication.TOPIC)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaEvolutionTest {

  // A real registry starts every subject at BACKWARD; the mock starts at NONE, so say it out loud.
  @BeforeAll
  static void backwardCompatibility() throws Exception {
    MockSchemaRegistry.getClientForScope("schema-evolution-test").updateCompatibility("orders-value", "BACKWARD");
  }

  @Autowired Publisher publisher;
  @Autowired Reader reader;

  @Test
  @Order(1)
  void newConsumerReadsOldMessageAndGetsTheDefault() throws Exception {
    publisher.publish(1, "o1", "pizza", "");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(find(reader.newConsumer, "o1")).hasToString("{\"id\": \"o1\", \"item\": \"pizza\", \"note\": \"\"}");
      assertThat(find(reader.oldConsumer, "o1")).hasToString("{\"id\": \"o1\", \"item\": \"pizza\"}");
    });
  }

  @Test
  @Order(2)
  void oldConsumerStillReadsNewMessageAndIgnoresTheNewField() throws Exception {
    publisher.publish(2, "o2", "burger", "extra-cheese");

    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
      assertThat(find(reader.newConsumer, "o2")).hasToString("{\"id\": \"o2\", \"item\": \"burger\", \"note\": \"extra-cheese\"}");
      assertThat(find(reader.oldConsumer, "o2")).hasToString("{\"id\": \"o2\", \"item\": \"burger\"}");
    });
  }

  // The mock registry only answers the compatibility API; it does not refuse a register() call the way a real
  // registry does (HTTP 409, verified by hand against cp-schema-registry, see README). So we ask the same question
  // the registry asks on every register: can the new schema read data written with the earlier ones?
  @Test
  @Order(3)
  void registryCompatibilityCheckAcceptsADefaultedFieldAndRejectsAFieldWithoutOne() throws Exception {
    var registry = MockSchemaRegistry.getClientForScope("schema-evolution-test");

    assertThat(registry.testCompatibility("orders-value", new AvroSchema(Schemas.V2))).isTrue();
    assertThat(registry.testCompatibility("orders-value", new AvroSchema(Schemas.V3_BREAKING))).isFalse();
  }

  private static GenericRecord find(List<GenericRecord> records, String id) {
    return records.stream().filter(r -> id.equals(r.get("id").toString())).findFirst().orElse(null);
  }
}
