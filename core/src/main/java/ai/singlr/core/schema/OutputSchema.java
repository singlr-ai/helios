/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

/**
 * Wraps a Java class with its generated JSON Schema. Used to specify structured output requirements
 * for model responses.
 *
 * @param <T> the type of the output
 * @param type the class
 * @param schema the generated JSON Schema
 */
public record OutputSchema<T>(Class<T> type, JsonSchema schema) {

  /**
   * Creates an OutputSchema by generating a JSON Schema from the given class.
   *
   * @param clazz the record or class to use for structured output
   * @param <T> the type
   * @return an OutputSchema with the generated schema
   * @throws IllegalArgumentException if the class is a primitive, array, enum, or leaf type
   */
  public static <T> OutputSchema<T> of(Class<T> clazz) {
    var schema = SchemaGenerator.generate(clazz);
    return new OutputSchema<>(clazz, schema);
  }
}
