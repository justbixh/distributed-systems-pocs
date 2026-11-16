package com.example.kafkaoutbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaOutboxApplication {

  public static final String TOPIC = "orders";

  public static void main(String[] args) {
    SpringApplication.run(KafkaOutboxApplication.class, args);
  }

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
  }
}
