package com.example.kafkadeadletter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@SpringBootApplication
public class KafkaDeadLetterApplication {

  public static final String TOPIC = "orders";
  public static final String DLT = TOPIC + ".DLT";
  // values are Integers; a non-integer payload is a poison pill that fails in the deserializer, before our code runs
  public static final String QUANTITIES = "quantities";
  // failures here are retried through retry topics instead of blocking the partition
  public static final String SHIPMENTS = "shipments";

  public static void main(String[] args) {
    SpringApplication.run(KafkaDeadLetterApplication.class, args);
  }

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
  }

  // The dead-letter topic needs as many partitions as "orders": a failed record goes to the SAME partition number.
  @Bean
  NewTopic deadLetterTopic() {
    return TopicBuilder.name(DLT).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic quantitiesTopic() {
    return TopicBuilder.name(QUANTITIES).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic shipmentsTopic() {
    return TopicBuilder.name(SHIPMENTS).partitions(3).replicas(1).build();
  }

  // Spring Boot plugs this into every @KafkaListener. A failing record is retried 2 more times, 500 ms apart,
  // then sent to "orders.DLT" and the listener moves on to the next record.
  @Bean
  @SuppressWarnings({ "unchecked", "rawtypes" })
  DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
    // A record that failed to deserialize is re-sent as the original raw bytes, so it needs a byte[] template.
    var bytes = new KafkaTemplate(kafka.getProducerFactory().copyWithConfigurationOverride(Map.of("value.serializer", ByteArraySerializer.class)));
    Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
    templates.put(String.class, kafka);
    templates.put(byte[].class, bytes);
    var recoverer = new DeadLetterPublishingRecoverer(templates, (record, e) -> new TopicPartition(DLT, record.partition()));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(500, 2));
  }
}
