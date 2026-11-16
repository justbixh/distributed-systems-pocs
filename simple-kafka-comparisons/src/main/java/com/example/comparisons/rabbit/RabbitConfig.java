package com.example.comparisons.rabbit;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.comparisons.Handler;

/**
 * The retry loop is built from queues, using the default exchange ("") where routing key = queue name:
 *
 *   orders --(consumer rejects)--> orders.wait --(sits 300 ms, TTL expires)--> orders   ... and around again
 *   orders.dlq: where the consumer puts a message after the last attempt
 */
@Configuration
class RabbitConfig {

  static final String ORDERS = "orders";
  static final String WAIT = "orders.wait";
  static final String DLQ = "orders.dlq";

  @Bean
  Queue orders() {
    // a rejected (not requeued) message is dead-lettered by the broker into the wait queue
    return QueueBuilder.durable(ORDERS).deadLetterExchange("").deadLetterRoutingKey(WAIT).build();
  }

  @Bean
  Queue waitQueue() {
    // nobody consumes this queue: the TTL is the retry delay, then the broker sends the message back to "orders"
    return QueueBuilder.durable(WAIT).ttl(Handler.RETRY_DELAY_MS).deadLetterExchange("").deadLetterRoutingKey(ORDERS).build();
  }

  @Bean
  Queue deadLetterQueue() {
    return QueueBuilder.durable(DLQ).build();
  }
}
