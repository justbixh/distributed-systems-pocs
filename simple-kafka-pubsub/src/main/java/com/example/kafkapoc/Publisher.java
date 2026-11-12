package com.example.kafkapoc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLISHER. Sends a message to the topic and doesn't know or care who reads it.
 *
 * curl -X POST "localhost:8080/publish?key=user-1" -d "pizza"
 */
@RestController
public class Publisher {

  private static final Logger log = LoggerFactory.getLogger(Publisher.class);

  private final KafkaTemplate<String, String> kafka;

  Publisher(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @PostMapping("/publish")
  String publish(@RequestParam(required = false) String key, @RequestBody String message) throws Exception {
    // Same key -> same partition -> guaranteed order for that key. No key -> sticky: a burst of messages stays on one partition.
    SendResult<String, String> result = kafka.send(KafkaPocApplication.TOPIC, key, message).get();

    var meta = result.getRecordMetadata();
    String where = "topic=%s partition=%d offset=%d".formatted(meta.topic(), meta.partition(), meta.offset());
    log.info("PUBLISHED  key={} value={} -> {}", key, message, where);
    return "stored at " + where + "\n";
  }
}
