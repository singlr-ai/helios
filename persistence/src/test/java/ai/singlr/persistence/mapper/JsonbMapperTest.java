/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonbMapperTest {

  @Test
  void toJsonbWithNullMap() {
    assertEquals("{}", JsonbMapper.toJsonb(null));
  }

  @Test
  void toJsonbWithEmptyMap() {
    assertEquals("{}", JsonbMapper.toJsonb(Map.of()));
  }

  @Test
  void toJsonbWithEntries() {
    var json = JsonbMapper.toJsonb(Map.of("key", "value"));
    assertTrue(json.contains("\"key\""));
    assertTrue(json.contains("\"value\""));
  }

  @Test
  void fromJsonbWithNull() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb(null));
  }

  @Test
  void fromJsonbWithBlank() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb(""));
    assertEquals(Map.of(), JsonbMapper.fromJsonb("  "));
  }

  @Test
  void fromJsonbWithEmptyObject() {
    assertEquals(Map.of(), JsonbMapper.fromJsonb("{}"));
  }

  @Test
  void fromJsonbRoundTrip() {
    var original = Map.of("agent", "test", "model", "gemini");
    var json = JsonbMapper.toJsonb(original);
    var parsed = JsonbMapper.fromJsonb(json);
    assertEquals(original, parsed);
  }

  @Test
  void fromJsonbWithInvalidJsonThrows() {
    assertThrows(IllegalArgumentException.class, () -> JsonbMapper.fromJsonb("not json"));
  }
}
