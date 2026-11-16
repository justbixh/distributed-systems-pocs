package com.example.kafkadeadletter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

/**
 * WORKER. Processes orders. A message whose value starts with "poison" always fails, like a bad payload would in real life,
 * until someone "fixes" it (see Replay).
 *
 * Without an error handler a poison message is retried 10 times and then silently skipped (lost).
 * With the handler in KafkaDeadLetterApplication it is retried 2 more times and then parked in "orders.DLT".
 */
@Component
public class Worker {

  private static final Logger log = LoggerFactory.getLogger(Worker.class);

  // kept only so the test can check what happened
  final List<String> processed = new CopyOnWriteArrayList<>();
  final List<String> deadLettered = new CopyOnWriteArrayList<>();
  final List<Integer> quantities = new CopyOnWriteArrayList<>();
  final List<String> shipmentAttempts = new CopyOnWriteArrayList<>();
  final List<String> shipmentsGivenUp = new CopyOnWriteArrayList<>();

  // what Replay needs to put dead letters back, and which values have been "fixed" since
  // shortcut: parked records live in memory and are lost on restart, upgrade to re-reading orders.DLT with a fresh consumer
  final List<ConsumerRecord<String, String>> parked = new CopyOnWriteArrayList<>();
  final Set<String> fixed = ConcurrentHashMap.newKeySet();

  @KafkaListener(topics = KafkaDeadLetterApplication.TOPIC, groupId = "worker")
  void work(ConsumerRecord<String, String> record) {
    log.info("PROCESSING partition={} offset={} value={}", record.partition(), record.offset(), record.value());
    if (record.value().startsWith("poison") && !fixed.contains(record.value())) {
      throw new IllegalStateException("cannot process " + record.value());
    }
    processed.add(record.value());
  }

  // Poison pill: ErrorHandlingDeserializer catches the deserialization failure and hands it to the error handler
  // (which sends it straight to the DLT, no retries: the same bytes can never succeed) instead of looping forever.
  @KafkaListener(topics = KafkaDeadLetterApplication.QUANTITIES, groupId = "worker", properties = {
      "value.deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
      "spring.deserializer.value.delegate.class=org.apache.kafka.common.serialization.IntegerDeserializer" })
  void quantity(ConsumerRecord<String, Integer> record) {
    log.info("QUANTITY partition={} offset={} value={}", record.partition(), record.offset(), record.value());
    quantities.add(record.value());
  }

  // Non-blocking retries: a failure moves the message to shipments-retry-0, then -retry-1, then shipments-dlt,
  // so the partition keeps flowing while the message waits out its backoff.
  @RetryableTopic(attempts = "3", backOff = @org.springframework.kafka.annotation.BackOff(delay = 300, multiplier = 2))
  @KafkaListener(topics = KafkaDeadLetterApplication.SHIPMENTS, groupId = "worker")
  void ship(ConsumerRecord<String, String> record) {
    log.info("SHIPPING topic={} value={}", record.topic(), record.value());
    shipmentAttempts.add(record.value());
    if ("poison".equals(record.value())) {
      throw new IllegalStateException("cannot ship " + record.value());
    }
  }

  @DltHandler
  void shipGaveUp(ConsumerRecord<String, String> record) {
    log.warn("SHIPMENT GAVE UP topic={} value={}", record.topic(), record.value());
    shipmentsGivenUp.add(record.value());
  }

  // Someone has to watch the dead-letter topic: alert, inspect, fix, republish.
  @KafkaListener(topics = KafkaDeadLetterApplication.DLT, groupId = "dlt-viewer")
  void deadLetter(ConsumerRecord<String, String> record) {
    log.warn("DEAD LETTER partition={} offset={} value={}", record.partition(), record.offset(), record.value());
    deadLettered.add(record.value());
    parked.add(record);
  }
}
