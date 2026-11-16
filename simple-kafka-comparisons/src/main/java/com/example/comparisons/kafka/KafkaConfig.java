package com.example.comparisons.kafka;

import java.util.Map;
import java.util.LinkedHashMap;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.example.comparisons.Handler;

@Configuration
class KafkaConfig {

  static final String ORDERS = "orders";
  static final String DLT = ORDERS + ".DLT";

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(ORDERS).partitions(3).replicas(1).build();
  }

  // Needs as many partitions as "orders": a failed record goes to the SAME partition number.
  @Bean
  NewTopic deadLetterTopic() {
    return TopicBuilder.name(DLT).partitions(3).replicas(1).build();
  }

  // BLOCKING retry: the failing record is retried on the consumer thread, so everything behind it on that partition waits.
  // Kafka itself doesn't count attempts: the error handler in our process does, and forgets on restart.
  @Bean
  DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
    var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, e) -> new TopicPartition(DLT, record.partition()));
    return new DefaultErrorHandler(recoverer, new FixedBackOff(Handler.RETRY_DELAY_MS, Handler.MAX_ATTEMPTS - 1));
  }
}
