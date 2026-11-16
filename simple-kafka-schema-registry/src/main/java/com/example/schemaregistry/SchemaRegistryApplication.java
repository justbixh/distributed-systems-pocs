package com.example.schemaregistry;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class SchemaRegistryApplication {

  public static final String TOPIC = "orders";

  public static void main(String[] args) {
    SpringApplication.run(SchemaRegistryApplication.class, args);
  }

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
  }
}
