/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionResultTest {

  @Test
  void constructorSetsFields() {
    var result = new ExecutionResult("out", "err", 1, "submitted");
    assertEquals("out", result.stdout());
    assertEquals("err", result.stderr());
    assertEquals(1, result.exitCode());
    assertEquals("submitted", result.submitted());
  }

  @Test
  void nullStdoutDefaultsToEmpty() {
    var result = new ExecutionResult(null, "err", 0, null);
    assertEquals("", result.stdout());
  }

  @Test
  void nullStderrDefaultsToEmpty() {
    var result = new ExecutionResult("out", null, 0, null);
    assertEquals("", result.stderr());
  }

  @Test
  void hasTypeSuccessWhenExitCodeZero() {
    assertTrue(ExecutionResult.success("ok").hasTypeSuccess());
  }

  @Test
  void hasTypeSuccessFalseWhenNonZero() {
    assertFalse(ExecutionResult.failure("err").hasTypeSuccess());
  }

  @Test
  void hasSubmittedValueTrue() {
    assertTrue(ExecutionResult.success("ok", "answer").hasSubmittedValue());
  }

  @Test
  void hasSubmittedValueFalse() {
    assertFalse(ExecutionResult.success("ok").hasSubmittedValue());
  }

  @Test
  void successFactory() {
    var result = ExecutionResult.success("hello");
    assertEquals("hello", result.stdout());
    assertEquals("", result.stderr());
    assertEquals(0, result.exitCode());
    assertNull(result.submitted());
  }

  @Test
  void successWithSubmittedFactory() {
    var result = ExecutionResult.success("out", "answer");
    assertEquals("out", result.stdout());
    assertEquals("answer", result.submitted());
    assertEquals(0, result.exitCode());
  }

  @Test
  void failureFactory() {
    var result = ExecutionResult.failure("error msg");
    assertEquals("", result.stdout());
    assertEquals("error msg", result.stderr());
    assertEquals(1, result.exitCode());
  }

  @Test
  void failureWithExitCodeFactory() {
    var result = ExecutionResult.failure("oom", 137);
    assertEquals("", result.stdout());
    assertEquals("oom", result.stderr());
    assertEquals(137, result.exitCode());
  }

  @Test
  void builderDefaults() {
    var result = ExecutionResult.newBuilder().build();
    assertEquals("", result.stdout());
    assertEquals("", result.stderr());
    assertEquals(0, result.exitCode());
    assertNull(result.submitted());
  }

  @Test
  void builderAllOptions() {
    var result =
        ExecutionResult.newBuilder()
            .withStdout("output")
            .withStderr("warning")
            .withExitCode(2)
            .withSubmitted("final")
            .build();
    assertEquals("output", result.stdout());
    assertEquals("warning", result.stderr());
    assertEquals(2, result.exitCode());
    assertEquals("final", result.submitted());
  }
}
