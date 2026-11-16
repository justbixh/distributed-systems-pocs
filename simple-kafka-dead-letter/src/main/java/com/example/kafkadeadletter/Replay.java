package com.example.kafkadeadletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REPLAY. After the bug is fixed, put the parked dead letters back on "orders" so they get processed for real.
 * The "fix" is simulated: replayed values are marked as fixed in the Worker.
 *
 * curl -X POST localhost:8080/replay
 */
@RestController
public class Replay {

  private static final Logger log = LoggerFactory.getLogger(Replay.class);

  private final KafkaTemplate<String, String> kafka;
  private final Worker worker;

  Replay(KafkaTemplate<String, String> kafka, Worker worker) {
    this.kafka = kafka;
    this.worker = worker;
  }

  @PostMapping("/replay")
  String replay() {
    int n = 0;
    for (var record : worker.parked) {
      worker.parked.remove(record);
      worker.fixed.add(record.value());
      kafka.send(KafkaDeadLetterApplication.TOPIC, record.key(), record.value());
      log.info("REPLAYED key={} value={}", record.key(), record.value());
      n++;
    }
    return "replayed " + n + "\n";
  }
}
