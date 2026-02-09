/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Converts between Java objects and JSONB strings for PostgreSQL. */
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

  /** Serializes any object to a JSON string suitable for JSONB columns. */
  public static String objectToJsonb(Object obj) {
    if (obj == null) {
      return null;
    }
    return MAPPER.writeValueAsString(obj);
  }

  private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE =
      new TypeReference<>() {};

  /** Deserializes a JSONB string to a {@code Map<String, Object>}. */
  public static Map<String, Object> fromJsonbObject(String json) {
    if (json == null || json.isBlank() || "{}".equals(json)) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, OBJECT_MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize object from JSON: " + json, e);
    }
  }

  private static final TypeReference<List<ToolCall>> TOOL_CALLS_TYPE = new TypeReference<>() {};

  /** Deserializes a JSONB string to a list of tool calls. */
  public static List<ToolCall> toolCallsFromJsonb(String json) {
    if (json == null || json.isBlank() || "[]".equals(json)) {
      return List.of();
    }
    try {
      return MAPPER.readValue(json, TOOL_CALLS_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize tool calls from JSON: " + json, e);
    }
  }
}
