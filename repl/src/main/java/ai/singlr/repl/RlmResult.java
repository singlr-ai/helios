/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.sandbox.ExecutionResult;
import java.util.List;

/**
 * Result of an {@link RlmHarness#run} invocation.
 *
 * @param <O> the typed output type
 * @param output the parsed output, or {@code null} when {@link #status()} is {@link Status#FAILED}
 * @param status how the run terminated. {@link Status#SUBMITTED} means the model called {@code
 *     submit()} cleanly; {@link Status#EXTRACTED} means the loop hit max iterations without a
 *     submit and {@link ExtractFallback} reconstituted the output from trajectory; {@link
 *     Status#FAILED} means neither produced a value
 * @param error error message when {@link #status()} is {@link Status#FAILED}; {@code null}
 *     otherwise
 * @param history the {@link ExecutionResult}s emitted by every {@code execute_code} call in the
 *     run, in order. Useful for telemetry, debugging, and post-mortem analysis
 * @param predictCallCount cumulative {@code predict()} calls during the run
 */
public record RlmResult<O>(
    O output, Status status, String error, List<ExecutionResult> history, int predictCallCount) {

  public RlmResult {
    history = history == null ? List.of() : List.copyOf(history);
  }

  public boolean success() {
    return status != Status.FAILED;
  }

  public enum Status {
    /** Model called {@code submit()} with a value that passed schema validation. */
    SUBMITTED,
    /** Loop exhausted without {@code submit()}; output reconstituted by {@link ExtractFallback}. */
    EXTRACTED,
    /** Neither submit nor extract-fallback produced a value. {@link #error()} explains why. */
    FAILED
  }
}
