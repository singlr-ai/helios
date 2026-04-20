/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MetricTest {

  @Test
  void exactMatchScoresOneWhenEqual() {
    var m = Metric.<String>exactMatch();
    assertEquals(1.0, m.score("a", "a", null));
  }

  @Test
  void exactMatchScoresZeroWhenDifferent() {
    var m = Metric.<String>exactMatch();
    assertEquals(0.0, m.score("a", "b", null));
  }

  @Test
  void exactMatchScoresOneWhenBothNull() {
    var m = Metric.<String>exactMatch();
    assertEquals(1.0, m.score(null, null, null));
  }

  @Test
  void exactMatchScoresZeroWhenExpectedNullAndActualNot() {
    var m = Metric.<String>exactMatch();
    assertEquals(0.0, m.score(null, "x", null));
  }

  @Test
  void exactMatchScoresZeroWhenActualNullAndExpectedNot() {
    var m = Metric.<String>exactMatch();
    assertEquals(0.0, m.score("x", null, null));
  }

  @Test
  void customMetricIsFunctional() {
    Metric<Integer> absDiff = (expected, actual, trace) -> -Math.abs(expected - actual);
    assertEquals(-3.0, absDiff.score(5, 2, null));
    assertEquals(0.0, absDiff.score(5, 5, null));
  }
}
