/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StepResultTest {

  @Test
  void successWithContent() {
    var result = StepResult.success("step1", "hello");

    assertEquals("step1", result.name());
    assertEquals("hello", result.content());
    assertEquals(Map.of(), result.data());
    assertTrue(result.success());
    assertNull(result.error());
  }

  @Test
  void successWithContentAndData() {
    var data = Map.of("key", "value");
    var result = StepResult.success("step1", "hello", data);

    assertEquals("step1", result.name());
    assertEquals("hello", result.content());
    assertEquals(data, result.data());
    assertTrue(result.success());
    assertNull(result.error());
  }

  @Test
  void failure() {
    var result = StepResult.failure("step1", "something went wrong");

    assertEquals("step1", result.name());
    assertNull(result.content());
    assertEquals(Map.of(), result.data());
    assertFalse(result.success());
    assertEquals("something went wrong", result.error());
  }

  @Test
  void skip() {
    var result = StepResult.skip("step1");

    assertEquals("step1", result.name());
    assertNull(result.content());
    assertEquals(Map.of(), result.data());
    assertTrue(result.success());
    assertNull(result.error());
  }

  @Test
  void equality() {
    var a = StepResult.success("s", "content");
    var b = StepResult.success("s", "content");
    assertEquals(a, b);
  }
}
