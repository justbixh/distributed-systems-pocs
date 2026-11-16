package com.example.comparisons;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Redis has no push listener, so RedisBroker polls on a schedule
public class ComparisonsApplication {

  public static void main(String[] args) {
    SpringApplication.run(ComparisonsApplication.class, args);
  }
}
