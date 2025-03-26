/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.util;

import ai.singlr.helios.model.base.FunctionObject;
import ai.singlr.helios.model.base.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class JsonUtils {

  private static final int MAX_DEPTH = 5;

  private static JsonNode emptyJson;
  private static ObjectMapper theMapper;

  private JsonUtils() {}

  static {
    theMapper = new ObjectMapper();
    emptyJson = theMapper.createObjectNode();
  }

  /**
   * Initialize the utility class with the provided object mapper.
   */
  public static void init(ObjectMapper mapper) {
    theMapper = mapper;
    emptyJson = mapper.createObjectNode();
  }

  public static JsonNode emptyJson() {
    return emptyJson;
  }

  public static ObjectMapper mapper() {
    return theMapper;
  }

  public static ObjectNode newJson() {
    return theMapper.createObjectNode();
  }

  public static ArrayNode newJsonArray() {
    return theMapper.createArrayNode();
  }

  public static <S> JsonSchema parse(String description, Object obj, Class<S> clazz) {
    ObjectNode objectNode = theMapper.convertValue(obj, ObjectNode.class);
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    Map<String, Object> properties = new HashMap<>();
    schema.put("properties", properties);
    schema.put("additionalProperties", false);

    buildSchema(objectNode, properties, 0);
    schema.put("required", properties.keySet());

    return new JsonSchema(description, clazz.getSimpleName(), schema, true);
  }

  public static <S> FunctionObject parseTool(String description, Object obj, Class<S> clazz) {
    ObjectNode objectNode = theMapper.convertValue(obj, ObjectNode.class);
    Map<String, Object> func = new HashMap<>();
    func.put("type", "object");
    Map<String, Object> properties = new HashMap<>();
    func.put("properties", properties);
    func.put("additionalProperties", false);

    buildSchema(objectNode, properties, 0);
    func.put("required", properties.keySet());

    return new FunctionObject(description, clazz.getSimpleName(), func, true);
  }

  private static void buildSchema(ObjectNode node, Map<String, Object> properties, int depth) {
    if (depth > MAX_DEPTH) {
      return;
    }

    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      Map<String, Object> fieldSchema = new HashMap<>();
      JsonNode value = field.getValue();

      if (value.isObject()) {
        fieldSchema.put("type", "object");
        Map<String, Object> subProperties = new HashMap<>();
        fieldSchema.put("properties", subProperties);
        fieldSchema.put("additionalProperties", false);
        buildSchema((ObjectNode) value, subProperties, depth + 1);
      } else if (value.isArray()) {
        fieldSchema.put("type", "array");
        JsonNode firstElement = value.elements().next();
        Map<String, Object> itemsSchema = new HashMap<>();
        itemsSchema.put("type", getType(firstElement));
        fieldSchema.put("items", itemsSchema);
      } else {
        fieldSchema.put("type", getType(value));
      }

      properties.put(field.getKey(), fieldSchema);
    }
  }

  private static String getType(JsonNode node) {
    if (node.isTextual()) {
      return "string";
    } else if (node.isInt() || node.isLong()) {
      return "integer";
    } else if (node.isBoolean()) {
      return "boolean";
    } else if (node.isDouble() || node.isFloat()) {
      return "number";
    } else if (node.isArray()) {
      return "array";
    } else {
      return "object";
    }
  }
}
