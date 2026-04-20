/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryExperimentLogTest {

  private static ExperimentEntry entry(int segment, String status, double metric) {
    return ExperimentEntry.newBuilder()
        .withSegment(segment)
        .withStatus(status)
        .withPrimaryMetric(metric)
        .build();
  }

  @Test
  void appendAndRead() {
    try (var log = new InMemoryExperimentLog()) {
      log.append(entry(0, "keep", 1.0));
      log.append(entry(0, "discard", 2.0));
      assertEquals(2, log.entries().size());
      assertEquals(1.0, log.entries().get(0).primaryMetric());
    }
  }

  @Test
  void entriesReturnsImmutableCopy() {
    try (var log = new InMemoryExperimentLog()) {
      log.append(entry(0, "keep", 1.0));
      var snapshot = log.entries();
      log.append(entry(0, "keep", 2.0));
      assertEquals(1, snapshot.size());
      assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
    }
  }

  @Test
  void segmentFilter() {
    try (var log = new InMemoryExperimentLog()) {
      log.append(entry(0, "keep", 1.0));
      log.newSegment();
      log.append(entry(1, "keep", 2.0));
      log.append(entry(1, "discard", 3.0));
      assertEquals(1, log.segment(0).size());
      assertEquals(2, log.segment(1).size());
      assertTrue(log.segment(42).isEmpty());
    }
  }

  @Test
  void currentSegmentStartsAtZero() {
    try (var log = new InMemoryExperimentLog()) {
      assertEquals(0, log.currentSegment());
    }
  }

  @Test
  void newSegmentIncrements() {
    try (var log = new InMemoryExperimentLog()) {
      assertEquals(1, log.newSegment());
      assertEquals(2, log.newSegment());
      assertEquals(2, log.currentSegment());
    }
  }

  @Test
  void concurrentAppends() throws Exception {
    try (var log = new InMemoryExperimentLog();
        var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      IntStream.range(0, 200)
          .forEach(i -> futures.add(exec.submit(() -> log.append(entry(0, "keep", (double) i)))));
      for (var f : futures) {
        f.get();
      }
      assertEquals(200, log.entries().size());
    }
  }

  @Test
  void closeIsIdempotent() {
    var log = new InMemoryExperimentLog();
    log.close();
    log.close();
  }
}
