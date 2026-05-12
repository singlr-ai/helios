/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredContentParserTest {

  /** A minimal adapter that parses JSON via a hand-rolled map shape — no Jackson in core tests. */
  private static final class MockAdapter implements StructuredContentParser.JsonAdapter {
    private final Map<String, Map<String, Object>> jsonFixtures;

    MockAdapter(Map<String, Map<String, Object>> fixtures) {
      this.jsonFixtures = fixtures;
    }

    @Override
    public Map<String, Object> toMap(String json) throws Exception {
      var fixture = jsonFixtures.get(json);
      if (fixture == null) {
        throw new RuntimeException("simulated JSON syntax error: " + json);
      }
      return fixture;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T fromMap(Map<String, Object> map, Class<T> type) throws Exception {
      if (type == Bag.class) {
        return (T) new Bag((String) map.get("name"), (Integer) map.get("count"));
      }
      throw new IllegalArgumentException("unsupported type: " + type);
    }
  }

  public record Bag(String name, int count) {}

  private static StructuredContentParser.JsonAdapter adapterFor(
      String json, Map<String, Object> map) {
    return new MockAdapter(Map.of(json, map));
  }

  private static StructuredContentParser.JsonAdapter throwingAdapter() {
    return new MockAdapter(Map.of());
  }

  // --- Null / blank passthrough ----------------------------------------------------------------

  @Test
  void nullContentReturnsNull() {
    var result =
        StructuredContentParser.parse(
            null, OutputSchema.of(Bag.class), throwingAdapter(), RuntimeException::new);
    assertNull(result);
  }

  @Test
  void blankContentReturnsNull() {
    var result =
        StructuredContentParser.parse(
            "   ", OutputSchema.of(Bag.class), throwingAdapter(), RuntimeException::new);
    assertNull(result);
  }

  // --- Success path ----------------------------------------------------------------------------

  @Test
  void successfulParseReturnsTyped() {
    var content = "{\"name\":\"alpha\",\"count\":7}";
    var adapter = adapterFor(content, new LinkedHashMap<>(Map.of("name", "alpha", "count", 7)));

    var result =
        StructuredContentParser.parse(
            content, OutputSchema.of(Bag.class), adapter, RuntimeException::new);

    assertEquals(new Bag("alpha", 7), result);
  }

  // --- Schema mismatch surfaces as StructuredOutputParseException ------------------------------

  @Test
  void schemaMismatchThrowsStructuredOutputParseException() {
    var content = "{\"name\":\"alpha\"}";
    var adapter = adapterFor(content, new LinkedHashMap<>(Map.of("name", "alpha")));
    // 'count' is missing — SchemaValidator should reject.

    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                StructuredContentParser.parse(
                    content, OutputSchema.of(Bag.class), adapter, RuntimeException::new));
    assertTrue(ex.errors().stream().anyMatch(e -> e.contains("count")));
  }

  // --- JSON syntax fallback through markdown strip ---------------------------------------------

  @Test
  void markdownWrappedJsonGetsStripped() {
    var rawInner = "{\"name\":\"beta\",\"count\":42}";
    var wrapped = "```json\n" + rawInner + "\n```";
    var fixtures =
        Map.<String, Map<String, Object>>of(
            // After strip we get the trimmed inner — that's what the adapter must accept.
            rawInner, new LinkedHashMap<>(Map.of("name", "beta", "count", 42)));
    var adapter = new MockAdapter(fixtures);

    var result =
        StructuredContentParser.parse(
            wrapped, OutputSchema.of(Bag.class), adapter, RuntimeException::new);

    assertEquals(new Bag("beta", 42), result);
  }

  @Test
  void unwrappedJsonSyntaxErrorThrowsProviderException() {
    // The adapter throws on the trimmed content and there's no markdown wrapper to strip — the
    // exception factory should fire.
    var content = "not-json";
    var ex =
        assertThrows(
            RuntimeException.class,
            () ->
                StructuredContentParser.parse(
                    content,
                    OutputSchema.of(Bag.class),
                    throwingAdapter(),
                    (msg, cause) -> new RuntimeException("provider wrap: " + msg, cause)));
    assertTrue(ex.getMessage().startsWith("provider wrap: Failed to parse structured output: "));
    assertTrue(ex.getMessage().contains(content));
  }

  @Test
  void wrappedJsonStillSyntacticallyInvalidThrowsProviderException() {
    // After stripping the fences the inner is still unparseable — provider exception fires.
    var content = "```json\nnot-json\n```";
    var ex =
        assertThrows(
            RuntimeException.class,
            () ->
                StructuredContentParser.parse(
                    content,
                    OutputSchema.of(Bag.class),
                    throwingAdapter(),
                    (msg, cause) -> new RuntimeException("wrap: " + msg, cause)));
    assertTrue(ex.getMessage().startsWith("wrap: Failed to parse structured output:"));
  }

  @Test
  void schemaMismatchInStrippedRetryStillThrowsParseException() {
    var inner = "{\"name\":\"y\"}";
    var wrapped = "```json\n" + inner + "\n```";
    // First attempt: adapter throws (raw content not in fixtures). Strip fences, try again with
    // the inner — adapter returns valid map but schema validation rejects missing count.
    var fixtures =
        Map.<String, Map<String, Object>>of(inner, new LinkedHashMap<>(Map.of("name", "y")));
    var adapter = new MockAdapter(fixtures);

    assertThrows(
        StructuredOutputParseException.class,
        () ->
            StructuredContentParser.parse(
                wrapped, OutputSchema.of(Bag.class), adapter, RuntimeException::new));
  }

  // --- stripMarkdownWrapper standalone -------------------------------------------------------

  @Test
  void stripMarkdownWrapperJsonFence() {
    assertEquals(
        "{\"name\":\"test\"}",
        StructuredContentParser.stripMarkdownWrapper("```json\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperPlainFence() {
    assertEquals(
        "{\"name\":\"test\"}",
        StructuredContentParser.stripMarkdownWrapper("```\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperWithoutFenceIsIdentity() {
    assertEquals(
        "{\"name\":\"test\"}", StructuredContentParser.stripMarkdownWrapper("{\"name\":\"test\"}"));
  }

  @Test
  void stripMarkdownWrapperTrimsWhitespace() {
    assertEquals("body", StructuredContentParser.stripMarkdownWrapper("```\n  body  \n```"));
  }

  @Test
  void stripMarkdownWrapperOnlyOpeningFence() {
    // The pre-1.x parser also stripped a trailing fence-less marker only when both ends matched.
    // The shared parser does it conservatively: leading fence removed, trailing left alone.
    assertEquals("{\"k\":1}", StructuredContentParser.stripMarkdownWrapper("```json\n{\"k\":1}"));
  }

  // --- Coverage of the provenanced fromMap path ------------------------------------------------

  @Test
  void provenancedSchemaRoutesThroughInnerOutputType() {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", "Alice");
    output.put("count", 5);
    var first = new LinkedHashMap<String, Object>();
    first.put("field", "name");
    first.put("sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of("alpha"))));
    first.put("reasoning", "stated");
    first.put("confidence", "HIGH");
    var second = new LinkedHashMap<String, Object>();
    second.put("field", "count");
    second.put("sources", List.of());
    second.put("reasoning", "inferred");
    second.put("confidence", "LOW");
    var prov = new java.util.ArrayList<Object>();
    prov.add(first);
    prov.add(second);
    var raw = new LinkedHashMap<String, Object>();
    raw.put("output", output);
    raw.put("provenance", prov);

    var content = "{\"output\":...}"; // matched only by exact key in fixtures
    var fixtures = new HashMap<String, Map<String, Object>>();
    fixtures.put(content, raw);

    var adapter = new MockAdapter(fixtures);
    var schema = OutputSchema.provenancedOf(Bag.class);

    @SuppressWarnings({"rawtypes", "unchecked"})
    var result =
        (ai.singlr.core.common.Provenanced<Bag>)
            StructuredContentParser.parse(content, schema, adapter, RuntimeException::new);

    assertEquals(new Bag("Alice", 5), result.output());
    assertEquals(2, result.provenance().size());
  }
}
