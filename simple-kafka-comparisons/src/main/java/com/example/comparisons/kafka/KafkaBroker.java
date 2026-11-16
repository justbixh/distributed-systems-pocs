package com.example.comparisons.kafka;

import static com.example.comparisons.kafka.KafkaConfig.DLT;
import static com.example.comparisons.kafka.KafkaConfig.ORDERS;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.example.comparisons.Broker;
import com.example.comparisons.Handler;

/**
 * KAFKA. The dead-letter topic is just another log. Nothing is ever "removed" from it: "parked" and "replay" are
 * defined by how far the consumer group "replay" has read. Same idea as ../simple-kafka-dead-letter.
 */
@Component("kafka")
public class KafkaBroker implements Broker {

  private static final String REPLAY_GROUP = "replay";

  private final KafkaTemplate<String, String> kafka;
  private final ConsumerFactory<String, String> consumers;
  private final Handler handler;

  KafkaBroker(KafkaTemplate<String, String> kafka, ConsumerFactory<String, String> consumers, Handler handler) {
    this.kafka = kafka;
    this.consumers = consumers;
    this.handler = handler;
  }

  @KafkaListener(topics = ORDERS, groupId = "worker")
  void work(String value) {
    handler.handle(value); // throwing hands the record to the error handler in KafkaConfig
  }

  @Override
  public void publish(String key, String value) throws Exception {
    // Same key -> same partition -> guaranteed order for that key.
    kafka.send(ORDERS, key, value).get();
  }

  // parked = what is in the DLT beyond the replay group's committed offset (its "lag")
  @Override
  public long parked() {
    try (var c = consumers.createConsumer(REPLAY_GROUP, "-parked")) {
      var partitions = dltPartitions(c.partitionsFor(DLT));
      var end = c.endOffsets(partitions);
      var done = c.committed(Set.copyOf(partitions)); // null for a partition nobody committed on yet
      return partitions.stream().mapToLong(p -> end.get(p) - (done.get(p) == null ? 0 : done.get(p).offset())).sum();
    }
  }

  @Override
  public int replay() throws Exception {
    int moved = 0;
    try (var c = consumers.createConsumer(REPLAY_GROUP, "-replay")) {
      c.assign(dltPartitions(c.partitionsFor(DLT)));
      for (var batch = c.poll(Duration.ofSeconds(2)); !batch.isEmpty(); batch = c.poll(Duration.ofSeconds(2))) {
        for (var record : batch) {
          handler.fix(record.value());
          kafka.send(ORDERS, record.key(), record.value()).get();
          moved++;
        }
      }
      c.commitSync(); // the dead letters stay in the topic, we only remember how far we replayed
    }
    return moved;
  }

  private static List<TopicPartition> dltPartitions(List<org.apache.kafka.common.PartitionInfo> infos) {
    return infos.stream().map(p -> new TopicPartition(DLT, p.partition())).toList();
  }
}
