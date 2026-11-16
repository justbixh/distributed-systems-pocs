package com.example.deliveryguarantees;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * EXACTLY-ONCE consume -> process -> produce. Reads "payments", writes a receipt to "receipts".
 *
 * Within Kafka, "consumed the input" and "produced the output" are made ONE atomic step: the output send and the input
 * offset commit are in the same Kafka transaction. Fail halfway and both are undone: the receipt is never visible
 * (to consumers with isolation.level=read_committed) and the payment is read again.
 *
 * This only covers Kafka -> Kafka. A database write or an HTTP call inside the listener is NOT part of the transaction;
 * for those, use the idempotent consumer (Inbox).
 */
@Component
public class Receipts {

  private static final Logger log = LoggerFactory.getLogger(Receipts.class);

  // kept only so the test can check what happened
  final List<String> attempts = new CopyOnWriteArrayList<>();
  final List<String> visibleReceipts = new CopyOnWriteArrayList<>();
  private final Set<String> alreadyFailed = ConcurrentHashMap.newKeySet();

  private final ExactlyOnceConfig.TxKafka tx;

  Receipts(ExactlyOnceConfig.TxKafka tx) {
    this.tx = tx;
  }

  @KafkaListener(topics = DeliveryGuaranteesApplication.PAYMENTS, groupId = "receipt-maker", containerFactory = "txListenerFactory")
  void issueReceipt(ConsumerRecord<String, String> record) {
    log.info("PAYMENT value={}", record.value());
    attempts.add(record.value());
    tx.template().send(DeliveryGuaranteesApplication.RECEIPTS, record.key(), "receipt:" + record.value());
    // a "flaky" payment fails once AFTER sending the receipt: the transaction aborts and that receipt must never be seen
    if (record.value().startsWith("flaky") && alreadyFailed.add(record.value())) {
      throw new IllegalStateException("failed after sending the receipt");
    }
  }

  // read_committed hides messages from aborted transactions; the default (read_uncommitted) would show them
  @KafkaListener(topics = DeliveryGuaranteesApplication.RECEIPTS, groupId = "receipt-viewer", properties = "isolation.level=read_committed")
  void receipt(ConsumerRecord<String, String> record) {
    log.info("RECEIPT value={}", record.value());
    visibleReceipts.add(record.value());
  }
}
