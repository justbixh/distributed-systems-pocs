package com.example.schemaregistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;

/**
 * READER. Two consumers of the same topic, each with the schema it was compiled against:
 * - "old" still uses v1 and has never heard of "note"
 * - "new" uses v2
 *
 * Each message carries the id of the schema it was WRITTEN with. The deserializer fetches that writer schema from the
 * registry and Avro translates it to the reader schema we pass in: extra fields are dropped, missing ones get their default.
 * We consume raw bytes (see application.yml) so we can pick the reader schema ourselves.
 */
@Component
public class Reader {

  private static final Logger log = LoggerFactory.getLogger(Reader.class);

  private final KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();

  // kept only so the test can check what happened
  final List<GenericRecord> oldConsumer = new CopyOnWriteArrayList<>();
  final List<GenericRecord> newConsumer = new CopyOnWriteArrayList<>();

  Reader(@Value("${spring.kafka.properties.schema.registry.url}") String registryUrl) {
    deserializer.configure(java.util.Map.of("schema.registry.url", registryUrl), false);
  }

  @KafkaListener(topics = SchemaRegistryApplication.TOPIC, groupId = "old-consumer")
  void old(byte[] value) {
    var order = read(value, Schemas.V1);
    log.info("OLD consumer (v1) got {}", order);
    oldConsumer.add(order);
  }

  @KafkaListener(topics = SchemaRegistryApplication.TOPIC, groupId = "new-consumer")
  void current(byte[] value) {
    var order = read(value, Schemas.V2);
    log.info("NEW consumer (v2) got {}", order);
    newConsumer.add(order);
  }

  private GenericRecord read(byte[] value, Schema readerSchema) {
    return (GenericRecord) deserializer.deserialize(SchemaRegistryApplication.TOPIC, value, readerSchema);
  }
}
