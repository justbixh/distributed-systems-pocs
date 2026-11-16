package com.example.comparisons.rabbit;

import static com.example.comparisons.rabbit.RabbitConfig.DLQ;
import static com.example.comparisons.rabbit.RabbitConfig.ORDERS;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.example.comparisons.Broker;
import com.example.comparisons.Handler;

/**
 * RABBITMQ. Queues, not logs: a message is deleted once acked. The broker counts the retries for us, in the
 * "x-death" header it adds every time a message is dead-lettered, but WE must read it and decide when to give up.
 * The retry is NON-blocking: while a failed message sits in "orders.wait", the next ones are processed.
 */
@Component("rabbit")
public class RabbitBroker implements Broker {

  private final RabbitTemplate rabbit;
  private final Handler handler;

  RabbitBroker(RabbitTemplate rabbit, Handler handler) {
    this.rabbit = rabbit;
    this.handler = handler;
  }

  @RabbitListener(queues = ORDERS)
  void work(Message message) {
    var value = new String(message.getBody(), UTF_8);
    try {
      handler.handle(value);
    } catch (RuntimeException e) {
      if (rejections(message) + 1 >= Handler.MAX_ATTEMPTS) {
        rabbit.convertAndSend(DLQ, value); // park it, then return normally so the original is acked and gone
      } else {
        throw new AmqpRejectAndDontRequeueException(e); // broker moves it to orders.wait, TTL brings it back
      }
    }
  }

  @Override
  public void publish(String key, String value) {
    // a single queue has no key-based ordering like Kafka partitions: the key is not used
    rabbit.convertAndSend(ORDERS, value);
  }

  @Override
  public long parked() {
    return rabbit.execute(channel -> channel.queueDeclarePassive(DLQ).getMessageCount());
  }

  // shortcut: receive() auto-acks, so a crash between receive and re-send loses the message; upgrade to a manual-ack channel
  @Override
  public int replay() {
    int moved = 0;
    for (Message m = rabbit.receive(DLQ); m != null; m = rabbit.receive(DLQ)) {
      var value = new String(m.getBody(), UTF_8);
      handler.fix(value);
      rabbit.convertAndSend(ORDERS, value);
      moved++;
    }
    return moved;
  }

  // x-death has one entry per (queue, reason); only "rejected from orders" counts a failed attempt
  // (the matching "expired from orders.wait" entry would double it)
  private static long rejections(Message message) {
    var deaths = message.getMessageProperties().getXDeathHeader();
    if (deaths == null) {
      return 0;
    }
    return deaths.stream().filter(d -> ORDERS.equals(d.get("queue"))).mapToLong(d -> (Long) d.get("count")).sum();
  }
}
