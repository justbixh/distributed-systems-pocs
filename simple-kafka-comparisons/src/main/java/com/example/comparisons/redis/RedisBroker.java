package com.example.comparisons.redis;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.comparisons.Broker;
import com.example.comparisons.Handler;

/**
 * REDIS STREAMS. A log like Kafka, with a consumer group like Kafka, but no built-in retry or dead-letter at all.
 * What Redis gives us: the Pending Entries List (PEL), the messages delivered to a consumer but never acked, with
 * a delivery counter. WE build the rest: re-claim a pending message after a delay, and move it to a "dead" stream
 * once the counter reaches the limit. Retries are NON-blocking: new messages keep being read meanwhile.
 */
@Component("redis")
public class RedisBroker implements Broker {

  private static final Logger log = LoggerFactory.getLogger(RedisBroker.class);

  private static final String STREAM = "orders";
  private static final String DEAD = "orders.dead";
  private static final String GROUP = "worker";
  private static final Consumer ME = Consumer.from(GROUP, "worker-1");

  private final StreamOperations<String, Object, Object> streams;
  private final Handler handler;

  RedisBroker(StringRedisTemplate redis, Handler handler) {
    this.streams = redis.opsForStream();
    this.handler = handler;
    try {
      streams.createGroup(STREAM, ReadOffset.from("0"), GROUP); // also creates the stream if it doesn't exist
    } catch (RuntimeException e) {
      if (!NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains("BUSYGROUP")) {
        throw e; // BUSYGROUP just means the group already exists from a previous run
      }
    }
  }

  @Override
  public void publish(String key, String value) {
    streams.add(StreamRecords.string(Map.of("key", key == null ? "" : key, "value", value)).withStreamKey(STREAM));
  }

  @Override
  public long parked() {
    return streams.size(DEAD);
  }

  @Override
  public int replay() {
    int moved = 0;
    for (var record : streams.range(DEAD, Range.unbounded())) {
      handler.fix((String) record.getValue().get("value"));
      streams.add(StreamRecords.newRecord().in(STREAM).ofMap(record.getValue()));
      streams.delete(DEAD, record.getId());
      moved++;
    }
    return moved;
  }

  // shortcut: one consumer polling every 100 ms; upgrade to StreamMessageListenerContainer or several consumers to scale out
  @Scheduled(fixedDelay = 100)
  void poll() {
    var fresh = streams.read(ME, StreamReadOptions.empty().count(10), StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
    if (fresh != null) {
      fresh.forEach(this::process);
    }

    // a failed message was never acked, so it sits in the PEL: retry it after the delay, or park it when out of attempts
    for (var pending : streams.pending(STREAM, GROUP, Range.unbounded(), 10)) {
      if (pending.getElapsedTimeSinceLastDelivery().toMillis() < Handler.RETRY_DELAY_MS) {
        continue;
      }
      if (pending.getTotalDeliveryCount() >= Handler.MAX_ATTEMPTS) {
        park(pending.getId().getValue());
      } else {
        // claiming also bumps the delivery counter, which is how Redis "counts" our attempts
        var claimed = streams.claim(STREAM, GROUP, ME.getName(), Duration.ofMillis(Handler.RETRY_DELAY_MS), pending.getId());
        claimed.forEach(this::process);
      }
    }
  }

  private void process(MapRecord<String, Object, Object> record) {
    try {
      handler.handle((String) record.getValue().get("value"));
      streams.acknowledge(STREAM, GROUP, record.getId());
    } catch (RuntimeException e) {
      log.warn("failed {}, left pending for a retry: {}", record.getId(), e.getMessage());
    }
  }

  private void park(String id) {
    for (var record : streams.range(STREAM, Range.just(id))) {
      streams.add(StreamRecords.newRecord().in(DEAD).ofMap(record.getValue())); // copy first, ack after: never lose it
      streams.acknowledge(STREAM, GROUP, record.getId());
    }
  }
}
