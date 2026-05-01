/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequiredPredictSignatureTest {

  @Test
  void twoArgConvenienceDefaultsRemediationToNull() {
    var sig = new RequiredPredictSignature("devils_advocate", "Take the opposing view...");
    assertEquals("devils_advocate", sig.name());
    assertEquals("Take the opposing view...", sig.instructions());
    assertNull(sig.remediation());
  }

  @Test
  void threeArgPreservesRemediation() {
    var sig =
        new RequiredPredictSignature("d", "instructions", "Run d() before submitting your final.");
    assertEquals("Run d() before submitting your final.", sig.remediation());
  }

  @Test
  void rejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature(null, "x"));
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature("", "x"));
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature("   ", "x"));
  }

  @Test
  void rejectsBlankInstructions() {
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature("name", null));
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature("name", ""));
    assertThrows(IllegalArgumentException.class, () -> new RequiredPredictSignature("name", "   "));
  }
}
