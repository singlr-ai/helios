/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.trace.Trace;
import ai.singlr.repl.sandbox.ExecutionResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeActResultTest {

  public record Output(String value) {}

  @Test
  void succeededFactoryCarriesOutputAndTrace() {
    var trace = sampleTrace();
    var execHistory = List.of(ExecutionResult.success("ok"));
    var hostFns = Map.of("kb_grep", 2);
    var output = new Output("hello");

    CodeActResult<Output> result = CodeActResult.succeeded(output, execHistory, hostFns, trace);

    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
    assertTrue(result.success());
    assertEquals(Optional.of(output), result.output());
    assertTrue(result.error().isEmpty());
    assertEquals(1, result.executionHistory().size());
    assertEquals(2, result.calledHostFunctions().get("kb_grep"));
    assertSame(trace, result.trace());
  }

  @Test
  void failedFactoryCarriesError() {
    var trace = sampleTrace();
    CodeActResult<Output> result = CodeActResult.failed("oops", List.of(), Map.of(), trace);

    assertEquals(CodeActResult.Status.FAILED, result.status());
    assertFalse(result.success());
    assertTrue(result.output().isEmpty());
    assertEquals(Optional.of("oops"), result.error());
    assertSame(trace, result.trace());
  }

  @Test
  void constructorRequiresStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodeActResult<>(null, Optional.empty(), Optional.empty(), null, null, null));
  }

  @Test
  void nullOptionalsDegradeToEmpty() {
    CodeActResult<Output> r =
        new CodeActResult<>(CodeActResult.Status.SUCCEEDED, null, null, null, null, null);
    assertEquals(Optional.empty(), r.output());
    assertEquals(Optional.empty(), r.error());
    assertEquals(List.of(), r.executionHistory());
    assertEquals(Map.of(), r.calledHostFunctions());
  }

  @Test
  void executionHistoryIsImmutable() {
    var r = CodeActResult.succeeded(new Output("x"), List.of(), Map.of(), null);
    assertThrows(
        UnsupportedOperationException.class,
        () -> r.executionHistory().add(ExecutionResult.success("y")));
  }

  @Test
  void calledHostFunctionsIsImmutable() {
    var r = CodeActResult.succeeded(new Output("x"), List.of(), Map.of(), null);
    assertThrows(UnsupportedOperationException.class, () -> r.calledHostFunctions().put("z", 1));
  }

  @Test
  void succeededWithNullOutputStillProducesEmptyOptional() {
    var r = CodeActResult.<Output>succeeded(null, List.of(), Map.of(), null);
    assertTrue(r.output().isEmpty());
    assertEquals(CodeActResult.Status.SUCCEEDED, r.status());
  }

  @Test
  void failedWithNullErrorStillProducesEmptyOptional() {
    var r = CodeActResult.<Output>failed(null, List.of(), Map.of(), null);
    assertTrue(r.error().isEmpty());
    assertEquals(CodeActResult.Status.FAILED, r.status());
  }

  private static Trace sampleTrace() {
    return Trace.newBuilder()
        .withId(UUID.randomUUID())
        .withName("test-trace")
        .withStartTime(OffsetDateTime.now())
        .withEndTime(OffsetDateTime.now())
        .build();
  }

  @Test
  void factoryProducesConsistentResultShape() {
    // Sanity smoke: cycle through factories to make sure the two paths agree on shape.
    var t = sampleTrace();
    assertNotNull(CodeActResult.succeeded(new Output("a"), List.of(), Map.of(), t).trace());
    assertNotNull(CodeActResult.failed("e", List.of(), Map.of(), t).trace());
  }
}
