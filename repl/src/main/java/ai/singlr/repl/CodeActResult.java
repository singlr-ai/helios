/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import ai.singlr.core.trace.Trace;
import ai.singlr.repl.sandbox.ExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of a {@link CodeActHarness#run} invocation.
 *
 * <p>Two terminal states: {@link Status#SUCCEEDED} when the model produced a final assistant
 * message that parsed against the configured {@link ai.singlr.core.schema.OutputSchema}, and {@link
 * Status#FAILED} otherwise (max iterations exhausted, schema parsing kept failing, or sandbox
 * crashed). There is no {@code EXTRACTED} status — unlike {@link RlmResult}, CodeAct doesn't run a
 * trajectory-replay fallback because the answer is structurally the final message rather than a
 * sandbox side-effect.
 *
 * @param <O> the typed output type
 * @param status how the run terminated
 * @param output the parsed output; present on {@link Status#SUCCEEDED}, empty otherwise
 * @param error error message when {@link #status()} is {@link Status#FAILED}; empty on success
 * @param executionHistory every {@link ExecutionResult} the {@code execute_code} tool produced in
 *     this run, in order. Always non-null
 * @param calledHostFunctions per-host-function call counts keyed by function name. Excludes
 *     framework-reserved names ({@code predict}, {@code submit}, {@code fetch}, {@code query},
 *     {@code __getInput}). Always non-null
 * @param trace the unified observability trace from this run (Agent loop + sub-spans)
 */
public record CodeActResult<O>(
    Status status,
    Optional<O> output,
    Optional<String> error,
    List<ExecutionResult> executionHistory,
    Map<String, Integer> calledHostFunctions,
    Trace trace) {

  public CodeActResult {
    if (status == null) {
      throw new IllegalArgumentException("status is required");
    }
    output = output == null ? Optional.empty() : output;
    error = error == null ? Optional.empty() : error;
    executionHistory = executionHistory == null ? List.of() : List.copyOf(executionHistory);
    calledHostFunctions = calledHostFunctions == null ? Map.of() : Map.copyOf(calledHostFunctions);
  }

  /** Factory for the success path. */
  public static <O> CodeActResult<O> succeeded(
      O output,
      List<ExecutionResult> executionHistory,
      Map<String, Integer> calledHostFunctions,
      Trace trace) {
    return new CodeActResult<>(
        Status.SUCCEEDED,
        Optional.ofNullable(output),
        Optional.empty(),
        executionHistory,
        calledHostFunctions,
        trace);
  }

  /** Factory for the failure path. */
  public static <O> CodeActResult<O> failed(
      String error,
      List<ExecutionResult> executionHistory,
      Map<String, Integer> calledHostFunctions,
      Trace trace) {
    return new CodeActResult<>(
        Status.FAILED,
        Optional.empty(),
        Optional.ofNullable(error),
        executionHistory,
        calledHostFunctions,
        trace);
  }

  public boolean success() {
    return status == Status.SUCCEEDED;
  }

  public enum Status {
    /** Model returned a final assistant message that parsed against the configured schema. */
    SUCCEEDED,
    /** Max iterations exhausted, schema retry budget exhausted, or sandbox failure. */
    FAILED
  }
}
