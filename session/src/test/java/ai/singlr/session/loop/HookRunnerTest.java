/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class HookRunnerTest {

  @Test
  void hookPhaseEnumDeclaresAllSevenPhases() {
    assertEquals(7, HookPhase.values().length);
    assertEquals(HookPhase.PRE_MODEL_TURN, HookPhase.valueOf("PRE_MODEL_TURN"));
  }

  @Test
  void constructorRejectsNullHooks() {
    var ex = assertThrows(NullPointerException.class, () -> new HookRunner(null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void emptyFactoryProducesEmptyRunner() {
    var runner = HookRunner.empty();
    assertTrue(runner.hooks().isEmpty());
  }

  @Test
  void hooksAccessorReturnsCallerList() {
    var supplied = List.<Object>of("hook-a", "hook-b");
    var runner = new HookRunner(supplied);
    assertSame(supplied, runner.hooks());
  }

  @Test
  void freshRunnerHasZeroFireCount() {
    assertEquals(0, HookRunner.empty().fireCount());
  }

  @Test
  void fireIncrementsCount() {
    var runner = HookRunner.empty();
    runner.fire(HookPhase.PRE_MODEL_TURN);
    runner.fire(HookPhase.POST_MODEL_TURN);
    runner.fire(HookPhase.ON_STREAM_EVENT);
    assertEquals(3, runner.fireCount());
  }

  @Test
  void fireRejectsNullPhase() {
    var ex = assertThrows(NullPointerException.class, () -> HookRunner.empty().fire(null));
    assertEquals("phase must not be null", ex.getMessage());
  }
}
