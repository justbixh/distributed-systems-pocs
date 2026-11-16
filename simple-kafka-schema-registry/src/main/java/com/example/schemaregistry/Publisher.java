package com.example.schemaregistry;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLISHER. Sends an Order written with schema version 1, 2 or 3 (the breaking one).
 *
 * On send, KafkaAvroSerializer registers the schema under subject "orders-value" in the Schema Registry. The registry
 * checks it against the earlier versions; if it is incompatible the send fails here, before anything reaches Kafka.
 *
 * curl -X POST "localhost:8080/publish?version=2&id=1&item=pizza&note=extra-cheese"
 */
@RestController
public class Publisher {

  private static final Logger log = LoggerFactory.getLogger(Publisher.class);

  private final KafkaTemplate<String, Object> kafka;

  Publisher(KafkaTemplate<String, Object> kafka) {
    this.kafka = kafka;
  }

  @PostMapping("/publish")
  String publish(@RequestParam int version, @RequestParam String id, @RequestParam String item,
      @RequestParam(defaultValue = "") String note) throws Exception {
    Schema schema = switch (version) {
      case 1 -> Schemas.V1;
      case 2 -> Schemas.V2;
      case 3 -> Schemas.V3_BREAKING;
      default -> throw new IllegalArgumentException("version must be 1, 2 or 3");
    };
    var order = new GenericData.Record(schema);
    order.put("id", id);
    order.put("item", item);
    if (version == 2) {
      order.put("note", note);
    }
    if (version == 3) {
      order.put("priority", 1);
    }

    var meta = kafka.send(SchemaRegistryApplication.TOPIC, id, order).get().getRecordMetadata();
    log.info("PUBLISHED v{} {} -> partition={} offset={}", version, order, meta.partition(), meta.offset());
    return "stored at partition=%d offset=%d\n".formatted(meta.partition(), meta.offset());
  }
}
