package com.example.comparisons;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Same three calls for every broker: {broker} is kafka, rabbit or redis.
 *
 * curl -X POST "localhost:8080/rabbit/publish?key=user-1" -d "poison"
 * curl localhost:8080/rabbit/parked
 * curl -X POST localhost:8080/rabbit/replay
 */
@RestController
public class Api {

  private final Map<String, Broker> brokers; // Spring fills this with bean name -> Broker: "kafka", "rabbit", "redis"

  Api(Map<String, Broker> brokers) {
    this.brokers = brokers;
  }

  @PostMapping("/{broker}/publish")
  String publish(@PathVariable String broker, @RequestParam(required = false) String key, @RequestBody String message)
      throws Exception {
    find(broker).publish(key, message);
    return "published to " + broker + "\n";
  }

  @GetMapping("/{broker}/parked")
  String parked(@PathVariable String broker) throws Exception {
    return find(broker).parked() + "\n";
  }

  @PostMapping("/{broker}/replay")
  String replay(@PathVariable String broker) throws Exception {
    return "replayed " + find(broker).replay() + "\n";
  }

  private Broker find(String name) {
    Broker broker = brokers.get(name);
    if (broker == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown broker, use one of " + brokers.keySet());
    }
    return broker;
  }
}
