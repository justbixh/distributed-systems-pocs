package com.example.schemaregistry;

import java.io.IOException;
import java.io.InputStream;

import org.apache.avro.Schema;

/** The three versions of the Order schema, loaded from src/main/resources/avro. */
public final class Schemas {

  public static final Schema V1 = load("order-v1.avsc");
  public static final Schema V2 = load("order-v2.avsc");
  public static final Schema V3_BREAKING = load("order-v3-breaking.avsc");

  private Schemas() {
  }

  private static Schema load(String file) {
    try (InputStream in = Schemas.class.getResourceAsStream("/avro/" + file)) {
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
