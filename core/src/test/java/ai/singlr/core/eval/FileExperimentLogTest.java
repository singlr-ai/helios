/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileExperimentLogTest {

  private static ExperimentEntry entry(int segment, ExperimentStatus status, double metric) {
    return ExperimentEntry.newBuilder()
        .withSegment(segment)
        .withStatus(status)
        .withPrimaryMetric(metric)
        .withDescription("d")
        .build();
  }

  @Test
  void appendWritesOneLinePerEntry(@TempDir Path dir) throws IOException {
    var path = dir.resolve("log.jsonl");
    try (var log = FileExperimentLog.open(path)) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
      log.append(entry(0, ExperimentStatus.DISCARD, 2.0));
    }
    var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).startsWith("{"));
  }

  @Test
  void reopenReplaysEntries(@TempDir Path dir) {
    var path = dir.resolve("log.jsonl");
    try (var log = FileExperimentLog.open(path)) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
      log.append(entry(0, ExperimentStatus.DISCARD, 2.0));
    }
    try (var log = FileExperimentLog.open(path)) {
      assertEquals(2, log.entries().size());
      assertEquals(1.0, log.entries().get(0).primaryMetric());
    }
  }

  @Test
  void reopenPreservesSegment(@TempDir Path dir) {
    var path = dir.resolve("log.jsonl");
    try (var log = FileExperimentLog.open(path)) {
      log.newSegment();
      log.append(entry(1, ExperimentStatus.KEEP, 1.0));
    }
    try (var log = FileExperimentLog.open(path)) {
      assertEquals(1, log.currentSegment());
      log.append(entry(1, ExperimentStatus.KEEP, 2.0));
      assertEquals(2, log.entries().size());
    }
  }

  @Test
  void newSegmentIncrements(@TempDir Path dir) {
    try (var log = FileExperimentLog.open(dir.resolve("log.jsonl"))) {
      assertEquals(0, log.currentSegment());
      assertEquals(1, log.newSegment());
      assertEquals(2, log.newSegment());
    }
  }

  @Test
  void createsParentDirectories(@TempDir Path dir) {
    var nested = dir.resolve("a/b/c/log.jsonl");
    try (var log = FileExperimentLog.open(nested)) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
    }
    assertTrue(Files.exists(nested));
  }

  @Test
  void appendAfterCloseThrows(@TempDir Path dir) {
    var log = FileExperimentLog.open(dir.resolve("log.jsonl"));
    log.close();
    assertThrows(
        IllegalStateException.class, () -> log.append(entry(0, ExperimentStatus.KEEP, 1.0)));
  }

  @Test
  void closeIsIdempotent(@TempDir Path dir) {
    var log = FileExperimentLog.open(dir.resolve("log.jsonl"));
    log.close();
    log.close();
  }

  @Test
  void segmentFilter(@TempDir Path dir) {
    try (var log = FileExperimentLog.open(dir.resolve("log.jsonl"))) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
      log.newSegment();
      log.append(entry(1, ExperimentStatus.KEEP, 2.0));
      log.append(entry(1, ExperimentStatus.DISCARD, 3.0));
      assertEquals(1, log.segment(0).size());
      assertEquals(2, log.segment(1).size());
      assertThrows(UnsupportedOperationException.class, () -> log.segment(0).add(null));
    }
  }

  @Test
  void entriesReturnsImmutableCopy(@TempDir Path dir) {
    try (var log = FileExperimentLog.open(dir.resolve("log.jsonl"))) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
      var snapshot = log.entries();
      log.append(entry(0, ExperimentStatus.KEEP, 2.0));
      assertNotEquals(log.entries().size(), snapshot.size());
    }
  }

  @Test
  void pathExposed(@TempDir Path dir) {
    var p = dir.resolve("log.jsonl");
    try (var log = FileExperimentLog.open(p)) {
      assertEquals(p, log.path());
    }
  }

  @Test
  void corruptLineRaises(@TempDir Path dir) throws IOException {
    var path = dir.resolve("log.jsonl");
    Files.writeString(path, "{not valid json\n", StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> FileExperimentLog.open(path));
  }

  @Test
  void blankLinesSkipped(@TempDir Path dir) throws IOException {
    var path = dir.resolve("log.jsonl");
    try (var log = FileExperimentLog.open(path)) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
    }
    Files.writeString(
        path, "\n\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    try (var log = FileExperimentLog.open(path)) {
      assertEquals(1, log.entries().size());
    }
  }

  @Test
  void openInRootStyleDirectoryWorks(@TempDir Path dir) {
    var p = Path.of(dir.toString(), "log.jsonl");
    try (var log = FileExperimentLog.open(p)) {
      log.append(entry(0, ExperimentStatus.KEEP, 1.0));
      assertEquals(1, log.entries().size());
    }
  }
}
