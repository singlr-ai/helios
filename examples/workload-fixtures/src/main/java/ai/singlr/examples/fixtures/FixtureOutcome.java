/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.fixtures;

import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.util.List;

/**
 * Raw bag of outputs from one fixture run. {@link FixtureRunner} consumes this and reduces it into
 * a {@link Metrics} record.
 *
 * <p>Carries the full trace + per-call executed code so the recovery-iteration heuristic can run
 * over it post-hoc rather than requiring each fixture to compute it directly.
 *
 * @param passed whether the fixture's correctness check held
 * @param totalIterations number of model iterations (proxy: count of {@code model.chat} spans on
 *     the trace)
 * @param executedCodeSnippets the source code the model emitted to {@code execute_code}, in call
 *     order. Empty for fixtures that don't use the REPL substrate
 * @param boundFieldNames the JShell binding names produced by {@code InputBindings} for this
 *     fixture's input record. Empty for fixtures that don't use the REPL substrate. Drives the
 *     recovery-iteration heuristic
 * @param trace unified trace from the run
 * @param duration wall-clock measured by the runner
 * @param notes free-form per-run annotations (e.g. {@code "extract-fallback fired"})
 */
public record FixtureOutcome(
    boolean passed,
    int totalIterations,
    List<String> executedCodeSnippets,
    List<String> boundFieldNames,
    Trace trace,
    Duration duration,
    List<String> notes) {

  public FixtureOutcome {
    executedCodeSnippets =
        executedCodeSnippets == null ? List.of() : List.copyOf(executedCodeSnippets);
    boundFieldNames = boundFieldNames == null ? List.of() : List.copyOf(boundFieldNames);
    notes = notes == null ? List.of() : List.copyOf(notes);
    if (duration == null || duration.isNegative()) {
      duration = Duration.ZERO;
    }
  }
}
