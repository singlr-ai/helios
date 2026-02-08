/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Converts between {@code Map<String, String>} and JSONB strings for PostgreSQL. */
public final class JsonbMapper {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private JsonbMapper() {}

  /** Serializes a map to a JSON string suitable for JSONB columns. */
  public static String toJsonb(Map<String, String> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    return MAPPER.writeValueAsString(map);
  }

  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  /** Deserializes a JSONB string to a map. */
  public static Map<String, String> fromJsonb(String json) {
    if (json == null || json.isBlank() || "{}".equals(json)) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize attributes from JSON: " + json, e);
    }
  }
}
