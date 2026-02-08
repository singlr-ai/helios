/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates JSON Schema from Java record classes. Supports primitive types, strings, lists, enums,
 * nested records, and annotations ({@link Description}, {@link Nullable}).
 */
public final class SchemaGenerator {

  private static final Set<Class<?>> INTEGER_TYPES =
      Set.of(int.class, Integer.class, long.class, Long.class, short.class, Short.class);

  private static final Set<Class<?>> NUMBER_TYPES =
      Set.of(double.class, Double.class, float.class, Float.class);

  private static final Set<Class<?>> BOOLEAN_TYPES = Set.of(boolean.class, Boolean.class);

  private SchemaGenerator() {}

  /**
   * Generates a JSON Schema from a Java record class.
   *
   * @param recordClass the record class to generate schema from
   * @return the generated JSON Schema
   * @throws IllegalArgumentException if the class is not a record
   */
  public static JsonSchema generate(Class<?> recordClass) {
    if (!recordClass.isRecord()) {
      throw new IllegalArgumentException("Class must be a record: " + recordClass.getName());
    }
    return generateForRecord(recordClass, new HashSet<>());
  }

  private static JsonSchema generateForRecord(Class<?> recordClass, Set<Class<?>> visited) {
    if (!visited.add(recordClass)) {
      throw new IllegalArgumentException("Circular record reference: " + recordClass.getName());
    }
    var components = recordClass.getRecordComponents();
    var properties = new LinkedHashMap<String, JsonSchema>();
    var required = new ArrayList<String>();

    for (var component : components) {
      var name = component.getName();
      var type = component.getGenericType();
      var schema = generateForType(type, visited);

      var descAnnotation = component.getAnnotation(Description.class);
      if (descAnnotation != null) {
        schema = schema.withDescription(descAnnotation.value());
      }

      properties.put(name, schema);

      if (component.getAnnotation(Nullable.class) == null) {
        required.add(name);
      }
    }

    var typeDescription =
        recordClass.isAnnotationPresent(Description.class)
            ? recordClass.getAnnotation(Description.class).value()
            : null;

    return new JsonSchema(
        "object",
        Map.copyOf(properties),
        null,
        required.isEmpty() ? null : List.copyOf(required),
        null,
        typeDescription,
        null);
  }

  private static JsonSchema generateForType(Type type, Set<Class<?>> visited) {
    if (type instanceof Class<?> clazz) {
      return generateForClass(clazz, visited);
    }

    if (type instanceof ParameterizedType paramType) {
      var rawType = (Class<?>) paramType.getRawType();

      if (List.class.isAssignableFrom(rawType)) {
        var itemType = paramType.getActualTypeArguments()[0];
        return JsonSchema.array(generateForType(itemType, visited));
      }

      if (Map.class.isAssignableFrom(rawType)) {
        return new JsonSchema("object", null, null, null, null, null, null);
      }
    }

    return JsonSchema.string();
  }

  private static JsonSchema generateForClass(Class<?> clazz, Set<Class<?>> visited) {
    if (clazz == String.class) {
      return JsonSchema.string();
    }

    if (INTEGER_TYPES.contains(clazz)) {
      return JsonSchema.integer();
    }

    if (NUMBER_TYPES.contains(clazz)) {
      return JsonSchema.number();
    }

    if (BOOLEAN_TYPES.contains(clazz)) {
      return JsonSchema.bool();
    }

    if (clazz.isEnum()) {
      var constants = clazz.getEnumConstants();
      var values = new ArrayList<String>(constants.length);
      for (var constant : constants) {
        values.add(((Enum<?>) constant).name());
      }
      return JsonSchema.enumOf(values);
    }

    if (clazz.isRecord()) {
      return generateForRecord(clazz, visited);
    }

    if (clazz.isArray()) {
      return JsonSchema.array(generateForClass(clazz.getComponentType(), visited));
    }

    return JsonSchema.string();
  }
}
