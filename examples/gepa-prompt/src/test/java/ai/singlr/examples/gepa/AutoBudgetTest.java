/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoBudgetTest {

  @Test
  void iterationsScaleWithPredictorCount() {
    var single = AutoBudget.MEDIUM.maxIterations(30, 1);
    var double_ = AutoBudget.MEDIUM.maxIterations(30, 2);
    assertEquals(single * 2, double_);
  }

  @Test
  void lightLessThanMediumLessThanHeavy() {
    var l = AutoBudget.LIGHT.maxIterations(30, 1);
    var m = AutoBudget.MEDIUM.maxIterations(30, 1);
    var h = AutoBudget.HEAVY.maxIterations(30, 1);
    assertTrue(l < m, "LIGHT < MEDIUM");
    assertTrue(m < h, "MEDIUM < HEAVY");
  }

  @Test
  void metricCallBudgetScalesWithValSetAndPredictors() {
    var b = AutoBudget.MEDIUM.maxMetricCalls(30, 1);
    var b2 = AutoBudget.MEDIUM.maxMetricCalls(60, 1);
    assertTrue(b2 > b, "doubling val size should grow budget");
  }

  @Test
  void heavyRoughlyDoubleMediumOnMetricCalls() {
    var m = AutoBudget.MEDIUM.maxMetricCalls(30, 1);
    var h = AutoBudget.HEAVY.maxMetricCalls(30, 1);
    assertTrue(h > m * 1.9 && h < m * 2.1, "HEAVY ≈ 2x MEDIUM, got h=" + h + " m=" + m);
  }

  @Test
  void rejectsBadValSetSize() {
    assertThrows(IllegalArgumentException.class, () -> AutoBudget.MEDIUM.maxIterations(0, 1));
    assertThrows(IllegalArgumentException.class, () -> AutoBudget.MEDIUM.maxMetricCalls(0, 1));
  }

  @Test
  void rejectsBadPredictorCount() {
    assertThrows(IllegalArgumentException.class, () -> AutoBudget.MEDIUM.maxIterations(30, 0));
    assertThrows(IllegalArgumentException.class, () -> AutoBudget.MEDIUM.maxMetricCalls(30, 0));
  }
}
