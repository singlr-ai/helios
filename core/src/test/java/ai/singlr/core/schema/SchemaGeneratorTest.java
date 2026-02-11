/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaGeneratorTest {

  record SimpleRecord(String name, int age) {}

  record AllPrimitives(
      String text,
      int intValue,
      Integer integerValue,
      long longValue,
      Long longObjectValue,
      double doubleValue,
      Double doubleObjectValue,
      float floatValue,
      boolean boolValue,
      Boolean boolObjectValue) {}

  record WithList(String name, List<String> tags) {}

  record WithNestedRecord(String id, SimpleRecord nested) {}

  record WithNullable(String required, @Nullable String optional) {}

  enum Color {
    RED,
    GREEN,
    BLUE
  }

  record WithEnum(String name, Color color) {}

  @Test
  void generateSimpleRecord() {
    var schema = SchemaGenerator.generate(SimpleRecord.class);

    assertEquals("object", schema.type());
    assertEquals(2, schema.properties().size());
    assertEquals("string", schema.properties().get("name").type());
    assertEquals("integer", schema.properties().get("age").type());
    assertEquals(List.of("name", "age"), schema.required());
  }

  @Test
  void generateAllPrimitives() {
    var schema = SchemaGenerator.generate(AllPrimitives.class);

    assertEquals("string", schema.properties().get("text").type());
    assertEquals("integer", schema.properties().get("intValue").type());
    assertEquals("integer", schema.properties().get("integerValue").type());
    assertEquals("integer", schema.properties().get("longValue").type());
    assertEquals("integer", schema.properties().get("longObjectValue").type());
    assertEquals("number", schema.properties().get("doubleValue").type());
    assertEquals("number", schema.properties().get("doubleObjectValue").type());
    assertEquals("number", schema.properties().get("floatValue").type());
    assertEquals("boolean", schema.properties().get("boolValue").type());
    assertEquals("boolean", schema.properties().get("boolObjectValue").type());
  }

  @Test
  void generateWithList() {
    var schema = SchemaGenerator.generate(WithList.class);

    assertEquals("array", schema.properties().get("tags").type());
    assertEquals("string", schema.properties().get("tags").items().type());
  }

  @Test
  void generateWithNestedRecord() {
    var schema = SchemaGenerator.generate(WithNestedRecord.class);

    var nested = schema.properties().get("nested");
    assertEquals("object", nested.type());
    assertEquals("string", nested.properties().get("name").type());
    assertEquals("integer", nested.properties().get("age").type());
  }

  @Test
  void generateWithNullable() {
    var schema = SchemaGenerator.generate(WithNullable.class);

    assertEquals(List.of("required"), schema.required());
    assertNotNull(schema.properties().get("optional"));
  }

  @Test
  void generateWithEnum() {
    var schema = SchemaGenerator.generate(WithEnum.class);

    var colorSchema = schema.properties().get("color");
    assertEquals("string", colorSchema.type());
    assertEquals(List.of("RED", "GREEN", "BLUE"), colorSchema.enumValues());
  }

  record SharedBadge(String label, String type) {}

  record ProfileItem(String name, List<SharedBadge> badges) {}

  record EventItem(String name, List<SharedBadge> badges) {}

  record MultiResponse(ProfileItem profile, EventItem event) {}

  @Test
  void generateWithSharedRecordAcrossSiblingBranches() {
    var schema = SchemaGenerator.generate(MultiResponse.class);

    var profileBadges = schema.properties().get("profile").properties().get("badges");
    var eventBadges = schema.properties().get("event").properties().get("badges");
    assertEquals("array", profileBadges.type());
    assertEquals("object", profileBadges.items().type());
    assertEquals("array", eventBadges.type());
    assertEquals("object", eventBadges.items().type());
  }

  record SelfReferencing(String value, SelfReferencing child) {}

  @Test
  void throwsForNonRecord() {
    assertThrows(IllegalArgumentException.class, () -> SchemaGenerator.generate(String.class));
  }

  @Test
  void throwsForCircularRecordReference() {
    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> SchemaGenerator.generate(SelfReferencing.class));
    assertTrue(exception.getMessage().contains("Circular record reference"));
  }

  @Description("A person profile")
  record DescribedRecord(
      @Description("Full legal name") String name, @Description("Age in years") int age) {}

  @Test
  void generateWithDescriptionOnComponents() {
    var schema = SchemaGenerator.generate(DescribedRecord.class);

    assertEquals("Full legal name", schema.properties().get("name").description());
    assertEquals("Age in years", schema.properties().get("age").description());
  }

  @Test
  void generateWithDescriptionOnType() {
    var schema = SchemaGenerator.generate(DescribedRecord.class);

    assertEquals("A person profile", schema.description());
  }

  @Test
  void toMapProducesValidJsonSchema() {
    var schema = SchemaGenerator.generate(SimpleRecord.class);
    var map = schema.toMap();

    assertEquals("object", map.get("type"));
    assertTrue(map.containsKey("properties"));
    assertTrue(map.containsKey("required"));
  }

  record WithShortAndBigInteger(short shortVal, BigInteger bigIntVal) {}

  @Test
  void generateWithShortAndBigInteger() {
    var schema = SchemaGenerator.generate(WithShortAndBigInteger.class);

    assertEquals("integer", schema.properties().get("shortVal").type());
    assertEquals("integer", schema.properties().get("bigIntVal").type());
  }

  record WithBigDecimal(BigDecimal price) {}

  @Test
  void generateWithBigDecimal() {
    var schema = SchemaGenerator.generate(WithBigDecimal.class);

    assertEquals("number", schema.properties().get("price").type());
  }

  record WithMap(String id, Map<String, Object> attributes) {}

  @Test
  void generateWithMapField() {
    var schema = SchemaGenerator.generate(WithMap.class);

    assertEquals("object", schema.properties().get("attributes").type());
  }

  record WithArray(String name, int[] scores) {}

  @Test
  void generateWithArrayField() {
    var schema = SchemaGenerator.generate(WithArray.class);

    var scoresSchema = schema.properties().get("scores");
    assertEquals("array", scoresSchema.type());
    assertEquals("integer", scoresSchema.items().type());
  }

  record WithStringArray(String[] tags) {}

  @Test
  void generateWithStringArrayField() {
    var schema = SchemaGenerator.generate(WithStringArray.class);

    var tagsSchema = schema.properties().get("tags");
    assertEquals("array", tagsSchema.type());
    assertEquals("string", tagsSchema.items().type());
  }

  record WithListOfRecords(List<SimpleRecord> items) {}

  @Test
  void generateWithListOfRecords() {
    var schema = SchemaGenerator.generate(WithListOfRecords.class);

    var itemsSchema = schema.properties().get("items");
    assertEquals("array", itemsSchema.type());
    assertEquals("object", itemsSchema.items().type());
    assertNotNull(itemsSchema.items().properties().get("name"));
  }

  record EmptyRecord() {}

  @Test
  void generateEmptyRecord() {
    var schema = SchemaGenerator.generate(EmptyRecord.class);

    assertEquals("object", schema.type());
    assertTrue(schema.properties().isEmpty());
  }

  record WithOptional(Optional<String> opt) {}

  @Test
  void throwsForUnsupportedGenericType() {
    assertThrows(
        IllegalArgumentException.class, () -> SchemaGenerator.generate(WithOptional.class));
  }

  record WithUnsupportedField(String name, Object payload) {}

  @Test
  void throwsForUnsupportedFieldType() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaGenerator.generate(WithUnsupportedField.class));
    assertTrue(exception.getMessage().contains("Unsupported type"));
  }

  @Test
  void throwsForUnsupportedPlainType() {
    assertThrows(IllegalArgumentException.class, () -> SchemaGenerator.generate(Object.class));
  }
}
