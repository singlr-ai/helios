/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TraceSamplerTest {

  private static TraceFeedback tf(double score, String label) {
    return new TraceFeedback(label, "exp", "act", score, "", null);
  }

  @Test
  void failuresFirstKeepsAllFailuresAndCapsSuccesses() {
    var traces =
        List.of(tf(0.0, "fail0"), tf(1.0, "ok0"), tf(0.5, "fail1"), tf(1.0, "ok1"), tf(1.0, "ok2"));
    var sampler = TraceSampler.failuresFirst(1.0, 2, new Random(42));
    var sampled = sampler.sample(traces);

    var failureCount = sampled.stream().filter(t -> t.score() < 1.0).count();
    var successCount = sampled.stream().filter(t -> t.score() >= 1.0).count();
    assertEquals(2, failureCount, "both failures kept");
    assertEquals(2, successCount, "at most 2 successes");
    // First entries in the sampled order must be failures (failures first, then successes).
    assertTrue(sampled.get(0).score() < 1.0);
    assertTrue(sampled.get(1).score() < 1.0);
  }

  @Test
  void failuresFirstHandlesFewerSuccessesThanCap() {
    var traces = List.of(tf(0.0, "fail0"), tf(1.0, "ok0"));
    var sampler = TraceSampler.failuresFirst(1.0, 5, new Random(0));
    var sampled = sampler.sample(traces);
    assertEquals(2, sampled.size());
  }

  @Test
  void failuresFirstZeroSuccessesKeepsOnlyFailures() {
    var traces = List.of(tf(0.0, "fail0"), tf(1.0, "ok0"));
    var sampler = TraceSampler.failuresFirst(1.0, 0, new Random(0));
    var sampled = sampler.sample(traces);
    assertEquals(1, sampled.size());
    assertEquals(0.0, sampled.get(0).score());
  }

  @Test
  void failuresFirstRejectsNullRng() {
    assertThrows(IllegalArgumentException.class, () -> TraceSampler.failuresFirst(1.0, 1, null));
  }

  @Test
  void failuresFirstRejectsNegativeMaxSuccesses() {
    assertThrows(
        IllegalArgumentException.class, () -> TraceSampler.failuresFirst(1.0, -1, new Random(0)));
  }
}
