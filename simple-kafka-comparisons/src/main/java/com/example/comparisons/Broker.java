package com.example.comparisons;

/**
 * The tiny contract every broker implements, so the same scenario can run on each one.
 * Behind it, each broker does failure handling its own way: that difference is what this project is about.
 */
public interface Broker {

  void publish(String key, String value) throws Exception;

  /** How many dead letters are waiting in the broker's dead-letter place right now. */
  long parked() throws Exception;

  /** Mark every dead letter as "fixed", put it back on the main queue, and return how many were moved. */
  int replay() throws Exception;
}
