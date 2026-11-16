package com.example.comparisons;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The business code, shared by all three brokers. A value containing "poison" always fails, like a bad payload would
 * in real life, until someone "fixes" it by replaying it. Same code, same retry numbers: only the broker differs.
 */
@Component
public class Handler {

  private static final Logger log = LoggerFactory.getLogger(Handler.class);

  /** 1 first try + 2 retries, then the message is parked. */
  public static final int MAX_ATTEMPTS = 3;
  public static final int RETRY_DELAY_MS = 300;

  // kept only so the test can check what happened
  final List<String> events = new CopyOnWriteArrayList<>(); // every attempt, in the order it ran
  final List<String> processed = new CopyOnWriteArrayList<>();
  final Set<String> fixed = ConcurrentHashMap.newKeySet();

  public void handle(String value) {
    log.info("PROCESSING {}", value);
    events.add(value);
    if (value.contains("poison") && !fixed.contains(value)) {
      throw new IllegalStateException("cannot process " + value);
    }
    processed.add(value);
  }

  /** Simulates deploying a bug fix: this value will succeed from now on. */
  public void fix(String value) {
    fixed.add(value);
  }

  long attempts(String value) {
    return events.stream().filter(value::equals).count();
  }
}
