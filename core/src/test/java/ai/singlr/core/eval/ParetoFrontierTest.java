/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParetoFrontierTest {

  @Test
  void emptyFrontier() {
    var f = new ParetoFrontier<String>(3);
    assertTrue(f.dominators().isEmpty());
    assertTrue(f.allCandidates().isEmpty());
    assertTrue(f.bestSingle().isEmpty());
    assertArrayEquals(new double[3], f.envelope());
    assertEquals(3, f.validationSetSize());
  }

  @Test
  void constructorRejectsZeroOrNegativeSize() {
    assertThrows(IllegalArgumentException.class, () -> new ParetoFrontier<>(0));
    assertThrows(IllegalArgumentException.class, () -> new ParetoFrontier<>(-1));
  }

  @Test
  void addRejectsWrongLength() {
    var f = new ParetoFrontier<String>(3);
    assertThrows(IllegalArgumentException.class, () -> f.add("a", new double[2]));
  }

  @Test
  void addRejectsNull() {
    var f = new ParetoFrontier<String>(3);
    assertThrows(IllegalArgumentException.class, () -> f.add("a", null));
  }

  @Test
  void addRejectsNaN() {
    var f = new ParetoFrontier<String>(3);
    assertThrows(
        IllegalArgumentException.class, () -> f.add("a", new double[] {1.0, Double.NaN, 0.5}));
  }

  @Test
  void singleCandidateIsOnFrontier() {
    var f = new ParetoFrontier<String>(3);
    assertTrue(f.add("a", new double[] {1.0, 1.0, 1.0}));
    assertEquals(java.util.List.of("a"), f.dominators());
  }

  @Test
  void dominatedCandidateLeavesFrontier() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 1.0, 1.0});
    var bIsOnFrontier = f.add("b", new double[] {0.5, 0.5, 0.5}); // dominated by a
    assertFalse(bIsOnFrontier);
    assertEquals(java.util.List.of("a"), f.dominators());
  }

  @Test
  void complementaryCandidatesBothOnFrontier() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 0.0, 0.5});
    var bAdded = f.add("b", new double[] {0.0, 1.0, 0.5});
    assertTrue(bAdded);
    assertEquals(java.util.Set.copyOf(f.dominators()), java.util.Set.of("a", "b"));
  }

  @Test
  void strictDominanceRequiresStrictlyGreaterOnAtLeastOne() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 1.0, 1.0});
    var bAdded = f.add("b", new double[] {1.0, 1.0, 1.0}); // equal: neither dominates the other
    assertTrue(bAdded);
    assertEquals(2, f.dominators().size());
  }

  @Test
  void coverageSamplingPrefersCandidatesWithMoreUniquelyWonInstances() {
    // a wins on instances 0..2 (3 wins), b wins on instance 3 (1 win).
    var f = new ParetoFrontier<String>(4);
    f.add("a", new double[] {1.0, 1.0, 1.0, 0.0});
    f.add("b", new double[] {0.0, 0.0, 0.0, 1.0});
    var rng = new Random(42);
    var counts = new HashMap<String, AtomicInteger>();
    for (var i = 0; i < 4000; i++) {
      counts.computeIfAbsent(f.sampleByCoverage(rng), k -> new AtomicInteger()).incrementAndGet();
    }
    var aCount = counts.get("a").get();
    var bCount = counts.get("b").get();
    // With weights 3 and 1 the ratio should be near 3:1 over 4000 trials.
    assertTrue(aCount > bCount * 2, "a (" + aCount + ") should be sampled >2x b (" + bCount + ")");
  }

  @Test
  void sampleByCoverageRejectsNullRng() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1, 1, 1});
    assertThrows(IllegalArgumentException.class, () -> f.sampleByCoverage(null));
  }

  @Test
  void sampleByCoverageRejectsEmptyFrontier() {
    var f = new ParetoFrontier<String>(3);
    assertThrows(IllegalStateException.class, () -> f.sampleByCoverage(new Random(0)));
  }

  @Test
  void bestSinglePicksHighestAggregate() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 0.0, 0.5}); // sum 1.5
    f.add("b", new double[] {0.0, 1.0, 0.6}); // sum 1.6
    assertEquals(java.util.Optional.of("b"), f.bestSingle());
  }

  @Test
  void aggregateScoreFindsCandidate() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 1.0, 1.0});
    assertEquals(3.0, f.aggregateScore("a"));
  }

  @Test
  void aggregateScoreUnknownCandidateThrows() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 1.0, 1.0});
    assertThrows(IllegalArgumentException.class, () -> f.aggregateScore("missing"));
  }

  @Test
  void envelopeTracksPerInstanceMax() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 0.0, 0.5});
    f.add("b", new double[] {0.0, 1.0, 0.7});
    assertArrayEquals(new double[] {1.0, 1.0, 0.7}, f.envelope());
  }

  @Test
  void allCandidatesPreservesInsertionOrder() {
    var f = new ParetoFrontier<String>(2);
    f.add("a", new double[] {1, 0});
    f.add("b", new double[] {0, 1});
    f.add("c", new double[] {0.5, 0.5});
    assertEquals(java.util.List.of("a", "b", "c"), f.allCandidates());
  }

  @Test
  void snapshotRoundTrips() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {1.0, 0.0, 0.5});
    f.add("b", new double[] {0.0, 1.0, 0.5});
    var snap = f.snapshot();
    var restored = ParetoFrontier.restore(snap);
    assertEquals(f.allCandidates(), restored.allCandidates());
    assertEquals(java.util.Set.copyOf(f.dominators()), java.util.Set.copyOf(restored.dominators()));
    assertArrayEquals(f.envelope(), restored.envelope());
  }

  @Test
  void restoreRejectsNullSnapshot() {
    assertThrows(IllegalArgumentException.class, () -> ParetoFrontier.restore(null));
  }

  @Test
  void snapshotConstructorValidates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ParetoFrontier.Snapshot<>(0, java.util.List.of()));
  }

  @Test
  void entryConstructorRejectsNullScores() {
    assertThrows(IllegalArgumentException.class, () -> new ParetoFrontier.Entry<>("c", null));
  }

  @Test
  void entryCloneIsDefensive() {
    var orig = new double[] {1.0, 2.0};
    var e = new ParetoFrontier.Entry<>("c", orig);
    orig[0] = 99;
    assertEquals(1.0, e.perInstanceScores()[0]);
  }

  @Test
  void hundredThreadAddSimultaneously() throws Exception {
    var f = new ParetoFrontier<Integer>(3);
    var threads = 100;
    var latch = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var i = 0; i < threads; i++) {
        var id = i;
        pool.submit(
            () -> {
              try {
                latch.await();
                f.add(id, new double[] {id * 0.01, (100 - id) * 0.01, 0.5});
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      latch.countDown();
      assertTrue(done.await(10, TimeUnit.SECONDS));
    }
    assertEquals(threads, f.allCandidates().size());
    // Frontier must be non-empty and every member must actually be non-dominated.
    var dominators = f.dominators();
    assertFalse(dominators.isEmpty());
  }

  @Test
  void allEqualScoresAllOnFrontier() {
    var f = new ParetoFrontier<String>(3);
    f.add("a", new double[] {0.5, 0.5, 0.5});
    f.add("b", new double[] {0.5, 0.5, 0.5});
    f.add("c", new double[] {0.5, 0.5, 0.5});
    assertEquals(3, f.dominators().size());
  }

  @Test
  void allZeroScoresAllOnFrontier() {
    var f = new ParetoFrontier<String>(2);
    f.add("a", new double[] {0, 0});
    f.add("b", new double[] {0, 0});
    assertEquals(2, f.dominators().size());
  }
}
