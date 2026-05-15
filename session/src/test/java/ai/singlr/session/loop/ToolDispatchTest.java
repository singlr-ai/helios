/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.session.ConcurrencyLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ToolDispatchTest {

  @Test
  void limitsAccessorReturnsConstructorValue() {
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(limits);
    assertSame(limits, d.limits());
  }

  @Test
  void permitCountsTrackLimits() {
    var limits = new ConcurrencyLimits(8, 2, 1, 32);
    var d = new ToolDispatch(limits);
    assertEquals(8, d.availableToolCallPermits());
    assertEquals(2, d.availableFileWritePermits());
    assertEquals(1, d.availableExecutionPermits());
  }

  @Test
  void constructorRejectsNullLimits() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolDispatch(null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullCall() {
    var d = new ToolDispatch(ConcurrencyLimits.defaults());
    var ex =
        assertThrows(NullPointerException.class, () -> d.dispatch(null, new CancellationToken()));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void dispatchRejectsNullCancellation() {
    var d = new ToolDispatch(ConcurrencyLimits.defaults());
    var call = new ToolCall("c", "read", Map.of());
    var ex = assertThrows(NullPointerException.class, () -> d.dispatch(call, null));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void dispatchThrowsUnsupportedInPhase1() {
    var d = new ToolDispatch(ConcurrencyLimits.defaults());
    var call = new ToolCall("c", "read", Map.of("p", "v"));
    var ex =
        assertThrows(
            UnsupportedOperationException.class, () -> d.dispatch(call, new CancellationToken()));
    assertTrue(ex.getMessage().startsWith("ToolDispatch.dispatch is unimplemented in Phase 1"));
  }
}
