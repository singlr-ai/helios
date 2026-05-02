/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PredictCallTest {

  @Test
  void canonicalConstructorPreservesFields() {
    var call = new PredictCall("instr", "input", 3);
    assertEquals("instr", call.instructions());
    assertEquals("input", call.input());
    assertEquals(3, call.iteration());
  }

  @Test
  void nullInstructionsBecomesEmpty() {
    var call = new PredictCall(null, "x", 0);
    assertEquals("", call.instructions());
  }

  @Test
  void nullInputBecomesEmpty() {
    var call = new PredictCall("x", null, 0);
    assertEquals("", call.input());
  }
}
