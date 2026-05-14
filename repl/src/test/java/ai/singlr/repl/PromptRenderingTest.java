/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.host.HostParameter;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptRenderingTest {

  public record Item(String name, int count) {}

  @Test
  void appendFieldsRendersBasicSchema() {
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, OutputSchema.of(Item.class));
    var rendered = sb.toString();
    assertTrue(rendered.contains("name"));
    assertTrue(rendered.contains("count"));
    assertTrue(rendered.contains("String"));
    assertTrue(rendered.contains("int"));
  }

  @Test
  void appendFieldsHandlesNullSchema() {
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, null);
    assertEquals("", sb.toString());
  }

  @Test
  void appendFieldsHandlesSchemaWithoutProperties() {
    var raw = OutputSchema.of(String.class, JsonSchema.string());
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, raw);
    assertEquals("", sb.toString());
  }

  @Test
  void appendFieldsMarksOptional() {
    var schema =
        OutputSchema.of(
            Item.class,
            JsonSchema.object()
                .withProperty("name", JsonSchema.string(), true)
                .withProperty("count", JsonSchema.integer(), false)
                .build());
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, schema);
    var rendered = sb.toString();
    assertTrue(rendered.contains("count (int) [optional]"));
    assertFalse(rendered.contains("name (String) [optional]"));
  }

  @Test
  void appendFieldsIncludesDescription() {
    var schema =
        OutputSchema.of(
            Item.class,
            JsonSchema.object()
                .withProperty("name", JsonSchema.string().withDescription("the label"), true)
                .build());
    var sb = new StringBuilder();
    PromptRendering.appendFields(sb, schema);
    assertTrue(sb.toString().contains("— the label"));
  }

  @Test
  void describeRendersEveryKnownType() {
    assertEquals("List<String>", PromptRendering.describe(JsonSchema.array(JsonSchema.string())));
    assertEquals("List<any>", PromptRendering.describe(JsonSchema.array(null)));
    assertEquals("object", PromptRendering.describe(JsonSchema.object().build()));
    assertEquals("int", PromptRendering.describe(JsonSchema.integer()));
    assertEquals("number", PromptRendering.describe(JsonSchema.number()));
    assertEquals("boolean", PromptRendering.describe(JsonSchema.bool()));
    assertEquals("String", PromptRendering.describe(JsonSchema.string()));
    assertEquals("enum [a, b]", PromptRendering.describe(JsonSchema.enumOf(List.of("a", "b"))));
  }

  @Test
  void describeHandlesNullAndUnknownTypes() {
    assertEquals("any", PromptRendering.describe(null));
    var noTypeSchema = new JsonSchema(null, null, null, null, null, null, null, null);
    assertEquals("any", PromptRendering.describe(noTypeSchema));
    var customTypeSchema = new JsonSchema("custom", null, null, null, null, null, null, null);
    assertEquals("custom", PromptRendering.describe(customTypeSchema));
  }

  @Test
  void appendCustomHostFunctionsSkipsReservedNames() {
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(
        sb, List.of(new HostFunction("predict", "reserved", params -> "")));
    assertEquals("", sb.toString(), "reserved names must be skipped");
  }

  @Test
  void appendCustomHostFunctionsHandlesNullAndEmpty() {
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, null);
    PromptRendering.appendCustomHostFunctions(sb, List.of());
    assertEquals("", sb.toString());
  }

  @Test
  void appendCustomHostFunctionsRendersOptionalAndDescribedParams() {
    var fn =
        new HostFunction(
            "lookup",
            "search the index",
            List.of(
                HostParameter.required("query", ParameterType.STRING, "what to search for"),
                HostParameter.optional("limit", ParameterType.INTEGER, "max results")),
            params -> "");
    var sb = new StringBuilder();
    PromptRendering.appendCustomHostFunctions(sb, List.of(fn));
    var rendered = sb.toString();
    assertTrue(rendered.contains("Custom host functions registered for this run:"));
    assertTrue(rendered.contains("lookup"));
    assertTrue(rendered.contains("— search the index"));
    assertTrue(rendered.contains("query (string) — what to search for"));
    assertTrue(rendered.contains("[optional] limit (integer) — max results"));
  }
}
