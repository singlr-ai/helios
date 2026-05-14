/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

  @Test
  void serializesAllFieldsInStableOrder() {
    var m = new Metrics("numeric-stats", "model-x", 1, true, 0, 3, 1200, 0, 4200, List.of("ok"));
    var json = JsonWriter.toJson(m);
    assertTrue(json.startsWith("{\"fixture\":\"numeric-stats\","));
    assertTrue(json.contains("\"model\":\"model-x\""));
    assertTrue(json.contains("\"attempt\":1"));
    assertTrue(json.contains("\"passed\":true"));
    assertTrue(json.contains("\"setupTurns\":0"));
    assertTrue(json.contains("\"totalIterations\":3"));
    assertTrue(json.contains("\"totalTokens\":1200"));
    assertTrue(json.contains("\"recoveryIterations\":0"));
    assertTrue(json.contains("\"durationMs\":4200"));
    assertTrue(json.endsWith("\"notes\":[\"ok\"]}"));
  }

  @Test
  void escapesQuotesAndControlChars() {
    var raw = "with \"quote\" and \nnewline";
    var escaped = JsonWriter.quote(raw);
    assertEquals("\"with \\\"quote\\\" and \\nnewline\"", escaped);
  }

  @Test
  void encodesNullAsLiteral() {
    assertEquals("null", JsonWriter.quote(null));
  }
}
