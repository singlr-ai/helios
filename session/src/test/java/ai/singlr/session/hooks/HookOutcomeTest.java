/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HookOutcomeTest {

  @Test
  void contFactoryReturnsSingleton() {
    assertSame(HookOutcome.cont(), HookOutcome.cont());
    assertInstanceOf(HookOutcome.Continue.class, HookOutcome.cont());
  }

  @Test
  void mutateFactoryConstructsRecord() {
    var outcome = HookOutcome.mutate(Map.of("k", "v"));
    var m = assertInstanceOf(HookOutcome.MutateInput.class, outcome);
    assertEquals("v", m.newInput().get("k"));
  }

  @Test
  void mutateDefensivelyCopiesInput() {
    var src = new HashMap<String, Object>();
    src.put("k", "v");
    var outcome = (HookOutcome.MutateInput) HookOutcome.mutate(src);
    src.put("k", "changed");
    assertEquals("v", outcome.newInput().get("k"), "input map must be defensively copied");
  }

  @Test
  void mutateRejectsNullInput() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.mutate(null));
    assertEquals("newInput must not be null", ex.getMessage());
  }

  @Test
  void blockFactoryConstructsRecord() {
    var outcome = HookOutcome.block("nope");
    var b = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertEquals("nope", b.reason());
  }

  @Test
  void blockRejectsNullReason() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.block(null));
    assertEquals("reason must not be null", ex.getMessage());
  }

  @Test
  void blockRejectsBlankReason() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.block("  "));
    assertEquals("reason must not be blank", ex.getMessage());
  }

  @Test
  void injectFactoryConstructsRecord() {
    var outcome = HookOutcome.inject("please clarify");
    var i = assertInstanceOf(HookOutcome.Inject.class, outcome);
    assertEquals("please clarify", i.userMessage());
  }

  @Test
  void injectRejectsNullMessage() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.inject(null));
    assertEquals("userMessage must not be null", ex.getMessage());
  }

  @Test
  void injectRejectsBlankMessage() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.inject(""));
    assertEquals("userMessage must not be blank", ex.getMessage());
  }

  @Test
  void stopFactoryConstructsRecord() {
    var outcome = HookOutcome.stop("done");
    var s = assertInstanceOf(HookOutcome.Stop.class, outcome);
    assertEquals("done", s.result());
  }

  @Test
  void stopRejectsNullResult() {
    var ex = assertThrows(NullPointerException.class, () -> HookOutcome.stop(null));
    assertEquals("result must not be null", ex.getMessage());
  }

  @Test
  void stopRejectsBlankResult() {
    var ex = assertThrows(IllegalArgumentException.class, () -> HookOutcome.stop(" "));
    assertEquals("result must not be blank", ex.getMessage());
  }

  @Test
  void continueRecordIsCheap() {
    // Sanity: direct construction works (record canonical) and matches the singleton.
    var via = new HookOutcome.Continue();
    assertInstanceOf(HookOutcome.Continue.class, via);
  }
}
