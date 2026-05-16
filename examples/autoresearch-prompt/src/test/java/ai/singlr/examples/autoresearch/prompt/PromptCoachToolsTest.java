/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.examples.autoresearch.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.eval.ExperimentEntry;
import ai.singlr.core.eval.ExperimentStatus;
import ai.singlr.core.eval.InMemoryCheckpoint;
import ai.singlr.core.eval.InMemoryExperimentLog;
import ai.singlr.core.eval.Objective;
import ai.singlr.core.eval.Score;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PromptCoachToolsTest {

  private static Objective<String> scoring(Map<String, Double> candidateScores) {
    return candidate -> {
      var s = candidateScores.get(candidate);
      if (s == null) {
        throw new IllegalStateException("no score for candidate: " + candidate);
      }
      return Score.of(s);
    };
  }

  @Test
  void tryPromptKeepsFirstEvaluation() {
    var best = new InMemoryCheckpoint<>("baseline");
    var bestScore = new AtomicReference<Double>();
    var log = new InMemoryExperimentLog();
    var tools = PromptCoachTools.create(scoring(Map.of("p1", 0.75)), best, bestScore, log, true);

    var result = tools.tryPrompt().execute(Map.of("candidate", "p1", "description", "d"));

    assertTrue(result.success());
    assertEquals("p1", best.current());
    assertEquals(0.75, bestScore.get());
    assertEquals(1, log.entries().size());
    assertEquals(ExperimentStatus.KEEP, log.entries().get(0).status());
  }

  @Test
  void tryPromptDiscardsWorseHigherIsBetter() {
    var best = new InMemoryCheckpoint<>("baseline");
    var bestScore = new AtomicReference<Double>(0.8);
    var log = new InMemoryExperimentLog();
    var tools = PromptCoachTools.create(scoring(Map.of("p1", 0.5)), best, bestScore, log, true);

    tools.tryPrompt().execute(Map.of("candidate", "p1", "description", "worse"));

    assertEquals("baseline", best.current());
    assertEquals(0.8, bestScore.get());
    assertEquals(ExperimentStatus.DISCARD, log.entries().get(0).status());
  }

  @Test
  void tryPromptLowerIsBetter() {
    var best = new InMemoryCheckpoint<>("baseline");
    var bestScore = new AtomicReference<Double>(100.0);
    var log = new InMemoryExperimentLog();
    var tools = PromptCoachTools.create(scoring(Map.of("p1", 80.0)), best, bestScore, log, false);

    tools.tryPrompt().execute(Map.of("candidate", "p1", "description", "faster"));

    assertEquals("p1", best.current());
    assertEquals(80.0, bestScore.get());
    assertEquals(ExperimentStatus.KEEP, log.entries().get(0).status());
  }

  @Test
  void tryPromptRejectsBlankCandidate() {
    var tools =
        PromptCoachTools.create(
            c -> Score.of(1.0),
            new InMemoryCheckpoint<>("x"),
            new AtomicReference<>(),
            new InMemoryExperimentLog(),
            true);
    var result = tools.tryPrompt().execute(Map.of("candidate", "  ", "description", "d"));
    assertFalse(result.success());
  }

  @Test
  void tryPromptRecordsAsi() {
    var log = new InMemoryExperimentLog();
    var tools =
        PromptCoachTools.create(
            c -> Score.of(1.0), new InMemoryCheckpoint<>("x"), new AtomicReference<>(), log, true);
    tools
        .tryPrompt()
        .execute(
            Map.of(
                "candidate",
                "cand",
                "description",
                "d",
                "asi",
                Map.of("hypothesis", "shorter helps", "tokens", 42)));
    var asi = log.entries().get(0).asi();
    assertEquals("shorter helps", asi.get("hypothesis"));
    assertEquals("42", asi.get("tokens"));
  }

  @Test
  void tryPromptCapturesObjectiveFailureAsCrash() {
    var log = new InMemoryExperimentLog();
    Objective<String> failing =
        c -> {
          throw new IllegalStateException("boom");
        };
    var tools =
        PromptCoachTools.create(
            failing, new InMemoryCheckpoint<>("x"), new AtomicReference<>(), log, true);
    var result = tools.tryPrompt().execute(Map.of("candidate", "cand", "description", "d"));
    assertTrue(result.success());
    assertEquals(ExperimentStatus.CRASH, log.entries().get(0).status());
    assertTrue(log.entries().get(0).asi().get("error").contains("boom"));
  }

  @Test
  void showBestReturnsCurrent() {
    var best = new InMemoryCheckpoint<>("initial prompt text");
    var bestScore = new AtomicReference<Double>(0.9);
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0), best, bestScore, new InMemoryExperimentLog(), true);
    var result = tools.showBest().execute(Map.of());
    assertTrue(result.output().contains("initial prompt text"));
    assertTrue(result.output().contains("0.9"));
  }

  @Test
  void showBestReportsNoScoreYet() {
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0),
            new InMemoryCheckpoint<>("x"),
            new AtomicReference<>(),
            new InMemoryExperimentLog(),
            true);
    var result = tools.showBest().execute(Map.of());
    assertTrue(result.output().contains("n/a"));
  }

  @Test
  void showLogEmpty() {
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0),
            new InMemoryCheckpoint<>("x"),
            new AtomicReference<>(),
            new InMemoryExperimentLog(),
            true);
    var result = tools.showLog().execute(Map.of());
    assertTrue(result.output().contains("log empty"));
  }

  @Test
  void showLogWithEntriesRespectsLimit() {
    var log = new InMemoryExperimentLog();
    for (int i = 0; i < 5; i++) {
      log.append(
          ExperimentEntry.newBuilder()
              .withStatus(ExperimentStatus.KEEP)
              .withPrimaryMetric(i)
              .withDescription("e" + i)
              .build());
    }
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0), new InMemoryCheckpoint<>("x"), new AtomicReference<>(), log, true);
    var result = tools.showLog().execute(Map.of("limit", 2));
    var text = result.output();
    assertFalse(text.contains("e0"));
    assertTrue(text.contains("e3"));
    assertTrue(text.contains("e4"));
  }

  @Test
  void showLogDefaultLimit() {
    var log = new InMemoryExperimentLog();
    log.append(
        ExperimentEntry.newBuilder()
            .withStatus(ExperimentStatus.KEEP)
            .withPrimaryMetric(1)
            .withDescription("one")
            .build());
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0), new InMemoryCheckpoint<>("x"), new AtomicReference<>(), log, true);
    var result = tools.showLog().execute(Map.of());
    assertTrue(result.output().contains("one"));
  }

  @Test
  void accessorsReturnTools() {
    var tools =
        PromptCoachTools.create(
            c -> Score.of(0.0),
            new InMemoryCheckpoint<>("x"),
            new AtomicReference<>(),
            new InMemoryExperimentLog(),
            true);
    assertNotNull(tools.tryPrompt());
    assertNotNull(tools.showBest());
    assertNotNull(tools.showLog());
    assertEquals("try_prompt", tools.tryPrompt().name());
    assertEquals("show_best", tools.showBest().name());
    assertEquals("show_log", tools.showLog().name());
  }

  @Test
  void tryPromptConfidenceReportedAfterThreeEntries() {
    var best = new InMemoryCheckpoint<>("baseline");
    var bestScore = new AtomicReference<Double>();
    var log = new InMemoryExperimentLog();
    var objective = scoring(Map.of("p1", 1.0, "p2", 2.0, "p3", 0.5, "p4", 10.0));
    var tools = PromptCoachTools.create(objective, best, bestScore, log, true);
    tools.tryPrompt().execute(Map.of("candidate", "p1", "description", "d"));
    tools.tryPrompt().execute(Map.of("candidate", "p2", "description", "d"));
    tools.tryPrompt().execute(Map.of("candidate", "p3", "description", "d"));
    var result = tools.tryPrompt().execute(Map.of("candidate", "p4", "description", "d"));
    var text = result.output();
    assertTrue(text.contains("confidence="), "expected confidence in output, got: " + text);
    assertNull(log.entries().get(0).confidence());
    assertNotNull(log.entries().get(3).confidence());
  }
}
