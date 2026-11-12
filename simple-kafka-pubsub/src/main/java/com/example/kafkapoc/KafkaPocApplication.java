package com.example.kafkapoc;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class KafkaPocApplication {

  public static final String TOPIC = "orders";

  public static void main(String[] args) {
    SpringApplication.run(KafkaPocApplication.class, args);
  }

  // Spring creates this topic on startup if it doesn't exist. 3 partitions = 3 parallel lanes.
  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
  }
}
