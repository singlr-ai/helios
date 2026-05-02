/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.repl.sandbox.ExecutionResult;
import java.util.List;
import java.util.Map;

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
 * @param predictCalls structured transcript of every {@code predict()} call the trajectory made, in
 *     call order, stamped with the iteration when each fired. Lets downstream RLM evaluators detect
 *     signature calls, count distinct sub-LLM invocations, or correlate predicts with specific
 *     {@link #history()} entries — all without grepping JShell source via {@link
 *     ExecutionResult#executedCode()}. Always non-null; empty when no predicts ran
 * @param calledHostFunctions per-host-function call counts keyed by function name. Excludes {@code
 *     predict} (use {@link #predictCalls()}) and {@code __getInput} (framework wiring). Includes
 *     {@code submit}, {@code fetch}, {@code query}, and every custom Skill-registered host function
 *     the model invoked. Always non-null; absent keys mean zero calls
 */
public record RlmResult<O>(
    O output,
    Status status,
    String error,
    List<ExecutionResult> history,
    int predictCallCount,
    List<PredictCall> predictCalls,
    Map<String, Integer> calledHostFunctions) {

  public RlmResult {
    history = history == null ? List.of() : List.copyOf(history);
    predictCalls = predictCalls == null ? List.of() : List.copyOf(predictCalls);
    calledHostFunctions = calledHostFunctions == null ? Map.of() : Map.copyOf(calledHostFunctions);
  }

  /** Convenience constructor pre-1.1.6, defaults the new trajectory fields to empty. */
  public RlmResult(
      O output, Status status, String error, List<ExecutionResult> history, int predictCallCount) {
    this(output, status, error, history, predictCallCount, List.of(), Map.of());
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
