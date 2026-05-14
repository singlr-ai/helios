/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraceFeedbackTest {

  @Test
  void blankFeedbackDegradesToEmptyString() {
    var t = new TraceFeedback("in", "exp", "act", 0.5, null, null);
    assertEquals("", t.feedback());
    var blank = new TraceFeedback("in", "exp", "act", 0.5, "   ", null);
    assertEquals("", blank.feedback());
  }

  @Test
  void nonBlankFeedbackPreserved() {
    var t = new TraceFeedback("in", "exp", "act", 0.5, "off by one", null);
    assertEquals("off by one", t.feedback());
  }
}
