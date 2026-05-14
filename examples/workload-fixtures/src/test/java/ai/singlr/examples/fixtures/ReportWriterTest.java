/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.examples.fixtures.tasks.NumericStatsFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

  @Test
  void renderProducesMarkdownTableWithFixtureSection() throws IOException {
    var fixtures = List.<Fixture>of(new NumericStatsFixture());
    var metrics =
        List.of(
            new Metrics(
                "numeric-stats",
                "gemini-3-flash-preview",
                1,
                true,
                1,
                3,
                1200,
                0,
                4200,
                List.of()));
    var report = ReportWriter.render(fixtures, metrics, null);
    assertNotNull(report);
    assertTrue(report.contains("## numeric-stats"));
    assertTrue(report.contains("gemini-3-flash-preview"));
    assertTrue(report.contains("100%"));
  }

  @Test
  void aggregateAveragesAcrossAttempts() {
    var a = new Metrics("f", "m", 1, true, 1, 4, 100, 0, 1000, List.of());
    var b = new Metrics("f", "m", 2, false, 1, 6, 200, 1, 3000, List.of());
    var cell = ReportWriter.aggregate(List.of(a, b));
    assertEquals(0.5, cell.passRate(), 1e-9);
    assertEquals(5.0, cell.avgIters(), 1e-9);
    assertEquals(150L, cell.avgTokens());
    assertEquals(0.5, cell.avgRecovery(), 1e-9);
    assertEquals(2000L, cell.avgMs());
  }

  @Test
  void deltaHandlesZeroBaselineSafely() {
    assertEquals(0.0, ReportWriter.delta(0, 0), 1e-9);
    assertEquals(1.0, ReportWriter.delta(5, 0), 1e-9);
    assertEquals(0.5, ReportWriter.delta(15, 10), 1e-9);
  }

  @Test
  void baselineLoadsFromJsonlAndProducesDeltaColumns(@TempDir Path tempDir) throws IOException {
    var baseline = tempDir.resolve("prior.jsonl");
    Files.writeString(
        baseline,
        JsonWriter.toJson(
                new Metrics(
                    "numeric-stats",
                    "gemini-3-flash-preview",
                    1,
                    true,
                    1,
                    3,
                    1000,
                    0,
                    5000,
                    List.of()))
            + "\n",
        StandardCharsets.UTF_8);

    var fixtures = List.<Fixture>of(new NumericStatsFixture());
    var current =
        List.of(
            new Metrics(
                "numeric-stats",
                "gemini-3-flash-preview",
                1,
                true,
                1,
                4,
                1500,
                0,
                5500,
                List.of()));
    var report = ReportWriter.render(fixtures, current, baseline);
    assertTrue(report.contains("Δ iters"));
    assertTrue(report.contains("Δ tokens"));
  }
}
