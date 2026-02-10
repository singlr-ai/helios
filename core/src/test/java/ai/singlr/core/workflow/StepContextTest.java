/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.SessionContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepContextTest {

  @Test
  void factoryWithInputOnly() {
    var ctx = StepContext.of("hello");

    assertEquals("hello", ctx.input());
    assertTrue(ctx.previousResults().isEmpty());
    assertNull(ctx.lastResult());
  }

  @Test
  void withResultAccumulatesInPreviousResults() {
    var ctx = StepContext.of("input");
    var r1 = StepResult.success("step1", "result1");
    var r2 = StepResult.success("step2", "result2");

    var ctx1 = ctx.withResult(r1);
    var ctx2 = ctx1.withResult(r2);

    assertEquals(1, ctx1.previousResults().size());
    assertEquals(r1, ctx1.previousResults().get("step1"));

    assertEquals(2, ctx2.previousResults().size());
    assertEquals(r1, ctx2.previousResults().get("step1"));
    assertEquals(r2, ctx2.previousResults().get("step2"));
  }

  @Test
  void withResultUpdatesLastResult() {
    var ctx = StepContext.of("input");
    var r1 = StepResult.success("step1", "result1");
    var r2 = StepResult.success("step2", "result2");

    var ctx1 = ctx.withResult(r1);
    assertEquals(r1, ctx1.lastResult());

    var ctx2 = ctx1.withResult(r2);
    assertEquals(r2, ctx2.lastResult());
  }

  @Test
  void withResultPreservesInput() {
    var ctx = StepContext.of("original input");
    var updated = ctx.withResult(StepResult.success("step1", "result"));

    assertEquals("original input", updated.input());
  }

  @Test
  void emptyPreviousResultsInitially() {
    var ctx = StepContext.of("input");
    assertEquals(Map.of(), ctx.previousResults());
  }

  @Test
  void factoryWithInputOnlyHasNullSession() {
    var ctx = StepContext.of("hello");
    assertNull(ctx.session());
  }

  @Test
  void factoryWithSession() {
    var session = SessionContext.of("test");
    var ctx = StepContext.of("hello", session);

    assertEquals("hello", ctx.input());
    assertEquals(session, ctx.session());
    assertTrue(ctx.previousResults().isEmpty());
    assertNull(ctx.lastResult());
  }

  @Test
  void withResultPreservesSession() {
    var session = SessionContext.of("test");
    var ctx = StepContext.of("input", session);
    var updated = ctx.withResult(StepResult.success("step1", "result"));

    assertEquals(session, updated.session());
  }
}
