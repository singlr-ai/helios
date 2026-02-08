/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolResultTest {

  @Test
  void successWithOutput() {
    var result = ToolResult.success("Done!");

    assertTrue(result.success());
    assertEquals("Done!", result.output());
    assertNull(result.data());
  }

  @Test
  void successWithData() {
    var data = java.util.Map.of("key", "value");
    var result = ToolResult.success("Result", data);

    assertTrue(result.success());
    assertEquals("Result", result.output());
    assertEquals(data, result.data());
  }

  @Test
  void failureWithMessage() {
    var result = ToolResult.failure("Something went wrong");

    assertFalse(result.success());
    assertEquals("Something went wrong", result.output());
    assertNull(result.data());
  }

  @Test
  void failureWithCause() {
    var cause = new RuntimeException("Root cause");
    var result = ToolResult.failure("Operation failed", cause);

    assertFalse(result.success());
    assertEquals("Operation failed: Root cause", result.output());
  }

  @Test
  void failureWithNullCause() {
    var result = ToolResult.failure("Operation failed", null);

    assertFalse(result.success());
    assertEquals("Operation failed", result.output());
  }

  @Test
  void failureWithCauseNoMessage() {
    var cause = new RuntimeException();
    var result = ToolResult.failure("Operation failed", cause);

    assertFalse(result.success());
    assertEquals("Operation failed", result.output());
  }
}
