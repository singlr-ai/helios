/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuiteRunnerArgsTest {

  @Test
  void parsesAllSupportedFlags() {
    var args =
        SuiteRunner.Args.parse(
            new String[] {
              "--providers", "gemini",
              "--fixtures", "numeric-stats,user-typed-sdtm",
              "--reps", "3",
              "--out", "reports/2026",
              "--baseline", "reports/prior/pass.jsonl"
            });
    assertEquals(List.of("gemini"), args.providers());
    assertEquals(List.of("numeric-stats", "user-typed-sdtm"), args.fixtures());
    assertEquals(3, args.reps());
    assertEquals("reports/2026", args.outDir());
    assertEquals(Path.of("reports/prior/pass.jsonl"), args.baseline());
  }

  @Test
  void defaultsApplyWhenNoFlagsSupplied() {
    var args = SuiteRunner.Args.parse(new String[] {});
    assertEquals(List.of("gemini"), args.providers());
    assertEquals(List.of(), args.fixtures());
    assertEquals(1, args.reps());
    assertNotNull(args.outDir());
    assertTrue(args.outDir().startsWith("reports/"));
    assertNull(args.baseline());
  }

  @Test
  void rejectsZeroOrNegativeReps() {
    assertThrows(
        IllegalArgumentException.class, () -> SuiteRunner.Args.parse(new String[] {"--reps", "0"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> SuiteRunner.Args.parse(new String[] {"--reps", "-1"}));
  }

  @Test
  void rejectsUnknownFlags() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SuiteRunner.Args.parse(new String[] {"--bogus", "yes"}));
  }

  @Test
  void rejectsMissingValueAfterFlag() {
    assertThrows(
        IllegalArgumentException.class, () -> SuiteRunner.Args.parse(new String[] {"--reps"}));
  }

  @Test
  void selectFixturesReturnsAllWhenRequestEmpty() {
    var all = SuiteRunner.selectFixtures(List.of());
    assertEquals(3, all.size());
  }

  @Test
  void selectFixturesByName() {
    var only = SuiteRunner.selectFixtures(List.of("user-typed-sdtm"));
    assertEquals(1, only.size());
    assertEquals("user-typed-sdtm", only.get(0).name());
  }

  @Test
  void selectFixturesRejectsUnknownName() {
    assertThrows(IllegalArgumentException.class, () -> SuiteRunner.selectFixtures(List.of("nope")));
  }
}
