/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InputBindingsTest {

  public record Stats(List<Integer> numbers, String operation) {}

  public record Empty() {}

  public static class Bean {
    public String name;
  }

  // Deliberately package-private. The whole point of the new design: bindings DON'T need this
  // to be accessible from JShell. The user's input record class never appears in JShell.
  record PackagePrivateRecord(int x, String y) {}

  // A record with a complex (user-defined) component type. Should fall back to Object binding.
  public record HasNestedRecord(int count, Stats nested) {}

  public record HasPrimitive(int count, boolean active) {}

  @Test
  void boundFieldNamesForRecord() {
    assertEquals(List.of("numbers", "operation"), InputBindings.boundFieldNames(Stats.class));
  }

  @Test
  void boundFieldNamesForNonRecordIsEmpty() {
    assertEquals(List.of(), InputBindings.boundFieldNames(Bean.class));
    assertEquals(List.of(), InputBindings.boundFieldNames(String.class));
    assertEquals(List.of(), InputBindings.boundFieldNames(null));
  }

  @Test
  void boundFieldNamesForEmptyRecordIsEmpty() {
    assertEquals(List.of(), InputBindings.boundFieldNames(Empty.class));
  }

  @Test
  void snippetReadsJsonAsMapNotAsUserClass() {
    var snippet = InputBindings.snippet(Stats.class, "{\"numbers\":[1,2,3],\"operation\":\"sum\"}");
    assertTrue(snippet.contains("var __raw"));
    assertTrue(
        snippet.contains("(java.util.Map<String, Object>) __mapper.readValue"),
        "snippet must read into Map, not user record class");
    assertFalse(
        snippet.contains(Stats.class.getCanonicalName() + ".class"),
        "snippet must NOT reference the user's record class — that would require accessibility");
  }

  @Test
  void snippetCastsSimpleFieldsToTheirDeclaredType() {
    var snippet = InputBindings.snippet(Stats.class, "{}");
    assertTrue(
        snippet.contains(
            "var numbers = (java.util.List<java.lang.Integer>) __raw.get(\"numbers\");"),
        "List<Integer> must render fully qualified for JShell, got:\n" + snippet);
    assertTrue(
        snippet.contains("var operation = (java.lang.String) __raw.get(\"operation\");"),
        "String must render as java.lang.String, got:\n" + snippet);
  }

  @Test
  void snippetBoxesPrimitiveTypesInCasts() {
    var snippet = InputBindings.snippet(HasPrimitive.class, "{}");
    assertTrue(
        snippet.contains("var count = (java.lang.Integer) __raw.get(\"count\");"),
        "primitive int must box to java.lang.Integer in cast");
    assertTrue(
        snippet.contains("var active = (java.lang.Boolean) __raw.get(\"active\");"),
        "primitive boolean must box to java.lang.Boolean in cast");
  }

  @Test
  void snippetSkipsCastForComplexTypes() {
    var snippet = InputBindings.snippet(HasNestedRecord.class, "{}");
    // count is int → simple → cast
    assertTrue(snippet.contains("var count = (java.lang.Integer) __raw.get(\"count\");"));
    // nested is Stats → user-defined → bound as Object (no cast)
    assertTrue(
        snippet.contains("var nested = __raw.get(\"nested\");"),
        "complex (user-defined) types must bind as Object — no cast — got:\n" + snippet);
    assertFalse(
        snippet.contains("(" + Stats.class.getCanonicalName() + ")"),
        "must not generate a cast that would require user class accessibility");
  }

  @Test
  void snippetEmbedsJsonAsBase64ToAvoidEscapeProblems() {
    var jsonWithQuotes = "{\"text\":\"He said \\\"hi\\\"\"}";
    var snippet = InputBindings.snippet(Stats.class, jsonWithQuotes);
    var b64Section = snippet.substring(snippet.indexOf("decode(\"") + 8);
    b64Section = b64Section.substring(0, b64Section.indexOf("\""));
    assertTrue(
        b64Section.matches("[A-Za-z0-9+/=]+"),
        "base64-encoded payload must contain only base64 alphabet, got: " + b64Section);
  }

  @Test
  void snippetWorksForPackagePrivateRecord() {
    // Key invariant: the user's record class doesn't need to be accessible from JShell.
    // The snippet must compile and not reference PackagePrivateRecord at all.
    var snippet = InputBindings.snippet(PackagePrivateRecord.class, "{\"x\":1,\"y\":\"hi\"}");
    assertFalse(
        snippet.contains("PackagePrivateRecord"),
        "snippet must NOT reference the user's input class name — it would be inaccessible");
    assertTrue(snippet.contains("var x = (java.lang.Integer) __raw.get(\"x\");"));
    assertTrue(snippet.contains("var y = (java.lang.String) __raw.get(\"y\");"));
  }

  @Test
  void snippetIsNullForNonRecord() {
    assertNull(InputBindings.snippet(Bean.class, "{}"));
    assertNull(InputBindings.snippet(String.class, "{}"));
    assertNull(InputBindings.snippet(null, "{}"));
  }

  @Test
  void snippetIsNullForEmptyRecord() {
    assertNull(InputBindings.snippet(Empty.class, "{}"));
  }

  @Test
  void componentsForRecord() {
    var components = InputBindings.components(Stats.class);
    assertEquals(2, components.length);
    assertEquals("numbers", components[0].getName());
    assertEquals("operation", components[1].getName());
  }

  @Test
  void componentsForNonRecordIsEmpty() {
    assertEquals(0, InputBindings.components(Bean.class).length);
    assertEquals(0, InputBindings.components(null).length);
  }

  @Test
  void isSimpleTypeForJavaPackageClasses() {
    assertTrue(InputBindings.isSimpleType(String.class));
    assertTrue(InputBindings.isSimpleType(Integer.class));
    assertTrue(InputBindings.isSimpleType(Boolean.class));
    assertTrue(InputBindings.isSimpleType(java.math.BigDecimal.class));
    assertTrue(InputBindings.isSimpleType(java.time.Instant.class));
  }

  @Test
  void isSimpleTypeForPrimitives() {
    assertTrue(InputBindings.isSimpleType(int.class));
    assertTrue(InputBindings.isSimpleType(boolean.class));
    assertTrue(InputBindings.isSimpleType(double.class));
  }

  @Test
  void isSimpleTypeForUserClassesIsFalse() {
    assertFalse(InputBindings.isSimpleType(Stats.class));
    assertFalse(InputBindings.isSimpleType(Bean.class));
  }

  @Test
  void isSimpleTypeForListOfSimpleIsTrue() throws Exception {
    var component = HasNestedRecord.class.getRecordComponents()[0]; // count: int
    assertTrue(InputBindings.isSimpleType(component.getGenericType()));
    var statsComponent = Stats.class.getRecordComponents()[0]; // numbers: List<Integer>
    assertTrue(InputBindings.isSimpleType(statsComponent.getGenericType()));
  }

  @Test
  void isSimpleTypeForListOfUserClassIsFalse() throws Exception {
    record HasListOfRecord(List<Stats> items) {}
    var listOfStats = HasListOfRecord.class.getRecordComponents()[0].getGenericType();
    assertFalse(InputBindings.isSimpleType(listOfStats));
  }

  @Test
  void renderTypeAsJavaSourceForCommonShapes() throws Exception {
    assertEquals("java.lang.String", InputBindings.renderTypeAsJavaSource(String.class));
    assertEquals("java.lang.Integer", InputBindings.renderTypeAsJavaSource(int.class));
    assertEquals("java.lang.Boolean", InputBindings.renderTypeAsJavaSource(boolean.class));

    var listOfInt = (ParameterizedType) Stats.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.List<java.lang.Integer>", InputBindings.renderTypeAsJavaSource(listOfInt));
  }

  @Test
  void renderTypeAsJavaSourceForArrays() {
    assertEquals("java.lang.Integer[]", InputBindings.renderTypeAsJavaSource(Integer[].class));
    assertEquals(
        "int[]".replace("int", "java.lang.Integer"),
        InputBindings.renderTypeAsJavaSource(int[].class));
  }

  @Test
  void renderTypeAsJavaSourceForNestedGenerics() throws Exception {
    record Wrap(Map<String, List<Integer>> mapping) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>",
        InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeAsJavaSourceForSetOfStrings() throws Exception {
    record Wrap(Set<String> tags) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals("java.util.Set<java.lang.String>", InputBindings.renderTypeAsJavaSource(t));
  }
}
