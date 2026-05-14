/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Smoke test that exercises {@link SuiteRunner#main} end-to-end with a one-fixture, one-rep
 * configuration so the harness shape is verified without spending real money on the full matrix.
 *
 * <p>Gated by {@code RUN_FIXTURES} AND {@code GEMINI_API_KEY} — both must be set. Pattern mirrors
 * the live-API integration tests in the other example modules. CI does NOT set {@code RUN_FIXTURES}
 * by default; this stays manual until we wire a scheduled pass.
 */
@EnabledIfEnvironmentVariable(named = "RUN_FIXTURES", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class SuiteRunnerTest {

  @Test
  void runsOneFixtureOneRepEndToEnd(@TempDir Path tempDir) throws IOException {
    var out = tempDir.resolve("smoke-pass");
    SuiteRunner.main(
        new String[] {
          "--fixtures", "numeric-stats",
          "--providers", "gemini",
          "--reps", "1",
          "--out", out.toString()
        });
    var jsonl = out.resolve("pass.jsonl");
    var markdown = out.resolve("pass.md");
    assertTrue(Files.exists(jsonl), "pass.jsonl was not written");
    assertTrue(Files.exists(markdown), "pass.md was not written");
    var lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
    assertEquals(1, lines.size(), "expected exactly one attempt line");
    assertTrue(lines.get(0).contains("\"fixture\":\"numeric-stats\""));
    var report = Files.readString(markdown, StandardCharsets.UTF_8);
    assertNotNull(report);
    assertTrue(report.contains("## numeric-stats"));
  }
}
