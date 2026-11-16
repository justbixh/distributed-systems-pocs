package com.example.deliveryguarantees;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.transaction.KafkaTransactionManager;

/**
 * Wiring for the exactly-once demo (see Receipts): a transactional producer, and a listener container that runs
 * every record inside a Kafka transaction.
 *
 * Deliberately NOT exposed as ProducerFactory / KafkaTemplate / transaction-manager beans: Spring Boot backs off its own
 * KafkaTemplate and JDBC transaction manager when it finds those, and the rest of the app needs them.
 */
@Configuration
class ExactlyOnceConfig {

  record TxKafka(KafkaTemplate<String, String> template, KafkaTransactionManager<String, String> manager) {}

  @Bean
  TxKafka txKafka(KafkaTemplate<String, String> kafka) {
    // a copy of the normal producer settings, plus a transactional.id prefix, which turns transactions on
    var factory = (DefaultKafkaProducerFactory<String, String>) kafka.getProducerFactory().copyWithConfigurationOverride(Map.of());
    factory.setTransactionIdPrefix("receipt-tx-");
    return new TxKafka(new KafkaTemplate<>(factory), new KafkaTransactionManager<>(factory));
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> txListenerFactory(ConsumerFactory<String, String> consumers, TxKafka tx) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumers);
    // the container begins a Kafka transaction per record, and commits the consumed offset INSIDE it
    factory.getContainerProperties().setKafkaAwareTransactionManager(tx.manager());
    return factory;
  }
}
