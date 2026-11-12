package com.example.kafkapoc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * SUBSCRIBERS. Two independent "services" listening to the same topic.
 *
 * Rule 1 (pub-sub):  different groupId  -> EVERY group gets EVERY message.
 * Rule 2 (sharing):  same groupId       -> the group's consumers SPLIT the partitions, each message goes to one of them.
 */
@Component
public class Subscribers {

  private static final Logger log = LoggerFactory.getLogger(Subscribers.class);

  // kept only so the test can check who received what
  final List<String> emailReceived = new CopyOnWriteArrayList<>();
  final List<String> analyticsReceived = new CopyOnWriteArrayList<>();

  // 1 consumer in the "email-service" group -> it owns all 3 partitions
  @KafkaListener(topics = KafkaPocApplication.TOPIC, groupId = "email-service")
  void email(ConsumerRecord<String, String> record) {
    print("email-service    ", record);
    emailReceived.add(record.value());
  }

  // 2 consumers (threads) in the "analytics-service" group -> they split the 3 partitions (2 + 1)
  @KafkaListener(topics = KafkaPocApplication.TOPIC, groupId = "analytics-service", concurrency = "2")
  void analytics(ConsumerRecord<String, String> record) {
    print("analytics-service", record);
    analyticsReceived.add(record.value());
  }

  private void print(String group, ConsumerRecord<String, String> r) {
    log.info("CONSUMED   group={} thread={} partition={} offset={} key={} value={}",
        group, Thread.currentThread().getName(), r.partition(), r.offset(), r.key(), r.value());
  }
}
