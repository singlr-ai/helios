/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import java.util.List;

/**
 * Per-attempt metrics emitted by {@link FixtureRunner} into {@code pass.jsonl} and aggregated into
 * {@code pass.md}.
 *
 * @param fixture the fixture's slug
 * @param model the model id that ran it (e.g. {@code gemini-3-flash-preview})
 * @param attempt 1-indexed attempt number when {@code --reps > 1}
 * @param passed did the fixture's correctness check hold
 * @param setupTurns leading model turns that produced no {@code execute_code} call — proxy for
 *     "cold-start orientation cost." For bare-Agent fixtures this is always 0
 * @param totalIterations total agent loop iterations consumed
 * @param totalTokens cumulative from {@link ai.singlr.core.trace.Trace#totalTokens()}
 * @param recoveryIterations executed-code snippets that contain a {@code (Type)} cast on a {@link
 *     FixtureOutcome#boundFieldNames() bound field name}. Heuristic for "model is fighting the
 *     binding"; the metric that motivated the suite
 * @param durationMs wall-clock around the fixture's {@code run(Model)} call
 * @param notes free-form annotations
 */
public record Metrics(
    String fixture,
    String model,
    int attempt,
    boolean passed,
    int setupTurns,
    int totalIterations,
    int totalTokens,
    int recoveryIterations,
    long durationMs,
    List<String> notes) {

  public Metrics {
    notes = notes == null ? List.of() : List.copyOf(notes);
  }
}
