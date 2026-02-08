/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

/**
 * Wraps a Java record class with its generated JSON Schema. Used to specify structured output
 * requirements for model responses.
 *
 * @param <T> the type of the output record
 * @param type the record class
 * @param schema the generated JSON Schema
 */
public record OutputSchema<T>(Class<T> type, JsonSchema schema) {

  /**
   * Creates an OutputSchema by generating a JSON Schema from the given record class.
   *
   * @param recordClass the record class to use for structured output
   * @param <T> the type of the record
   * @return an OutputSchema with the generated schema
   * @throws IllegalArgumentException if the class is not a record
   */
  public static <T> OutputSchema<T> of(Class<T> recordClass) {
    var schema = SchemaGenerator.generate(recordClass);
    return new OutputSchema<>(recordClass, schema);
  }
}
