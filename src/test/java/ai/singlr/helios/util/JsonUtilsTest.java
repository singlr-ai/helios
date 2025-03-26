/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonUtilsTest {

  record Person (
    String name,
    int age
  ) {}

  @Test
  @SuppressWarnings("unchecked")
  public void testLevelOneJsonSchema() throws Exception {
    var schema = JsonUtils.parse("Person schema", new Person("Alice", 30), Person.class);
    assertEquals("Person schema", schema.description());
    assertEquals("Person", schema.name());
    assertEquals(4, schema.schema().size());

    var properties = (Map<String, Object>) schema.schema().get("properties");
    assertEquals(2, properties.size());
    assertEquals("string", ((Map<String, Object>) properties.get("name")).get("type"));
    assertEquals("integer", ((Map<String, Object>) properties.get("age")).get("type"));
  }
}
