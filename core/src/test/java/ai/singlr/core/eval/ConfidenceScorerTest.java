/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfidenceScorerTest {

  private static ExperimentEntry entry(double metric) {
    return ExperimentEntry.newBuilder()
        .withStatus(ExperimentStatus.KEEP)
        .withPrimaryMetric(metric)
        .build();
  }

  @Test
  void nullForFewerThanThreeEntries() {
    assertNull(ConfidenceScorer.score(List.of()));
    assertNull(ConfidenceScorer.score(List.of(entry(1.0))));
    assertNull(ConfidenceScorer.score(List.of(entry(1.0), entry(2.0))));
  }

  @Test
  void nullForNullList() {
    assertNull(ConfidenceScorer.score((List<ExperimentEntry>) null));
  }

  @Test
  void nullWhenAllValuesEqual() {
    var entries = List.of(entry(5.0), entry(5.0), entry(5.0), entry(5.0));
    assertNull(ConfidenceScorer.score(entries));
  }

  @Test
  void improvementOverNoise() {
    var entries = List.of(entry(100.0), entry(101.0), entry(99.0), entry(80.0));
    var score = ConfidenceScorer.score(entries);
    assertTrue(score > 1.0, "expected confidence > 1.0, got " + score);
  }

  @Test
  void noImprovementScoresZero() {
    var entries = List.of(entry(50.0), entry(55.0), entry(60.0), entry(58.0));
    var score = ConfidenceScorer.score(entries);
    assertEquals(0.0, score);
  }

  @Test
  void scoreFromLogUsesCurrentSegment() {
    try (var log = new InMemoryExperimentLog()) {
      log.append(entry(10.0));
      log.append(entry(11.0));
      log.append(entry(9.0));
      log.newSegment();
      log.append(
          ExperimentEntry.newBuilder()
              .withSegment(1)
              .withStatus(ExperimentStatus.KEEP)
              .withPrimaryMetric(5.0)
              .build());
      assertNull(ConfidenceScorer.score(log));
    }
  }

  @Test
  void medianEvenLength() {
    var entries = List.of(entry(1.0), entry(2.0), entry(3.0), entry(100.0));
    var score = ConfidenceScorer.score(entries);
    assertTrue(score != null);
  }

  @Test
  void medianOddLength() {
    var entries = List.of(entry(1.0), entry(5.0), entry(3.0));
    var score = ConfidenceScorer.score(entries);
    assertTrue(score != null);
  }

  @Test
  void exactMadCalculation() {
    var entries = List.of(entry(10.0), entry(12.0), entry(8.0), entry(0.0));
    var score = ConfidenceScorer.score(entries);
    assertEquals(10.0 / 2.0, score, 1e-9);
  }
}
