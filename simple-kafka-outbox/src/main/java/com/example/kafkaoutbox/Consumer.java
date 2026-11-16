package com.example.kafkaoutbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class Consumer {

  final List<String> received = new CopyOnWriteArrayList<>();

  @KafkaListener(topics = KafkaOutboxApplication.TOPIC, groupId = "outbox-consumer")
  void on(String item) {
    System.out.println("received: " + item);
    received.add(item);
  }
}
