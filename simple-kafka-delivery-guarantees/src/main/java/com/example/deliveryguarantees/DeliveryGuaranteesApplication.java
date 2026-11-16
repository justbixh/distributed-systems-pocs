package com.example.deliveryguarantees;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // for the outbox poller
public class DeliveryGuaranteesApplication {

  public static final String ORDERS = "orders";
  public static final String PAYMENTS = "payments";
  public static final String RECEIPTS = "receipts";

  public static void main(String[] args) {
    SpringApplication.run(DeliveryGuaranteesApplication.class, args);
  }

  @Bean
  NewTopic ordersTopic() {
    return TopicBuilder.name(ORDERS).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic paymentsTopic() {
    return TopicBuilder.name(PAYMENTS).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic receiptsTopic() {
    return TopicBuilder.name(RECEIPTS).partitions(3).replicas(1).build();
  }
}
