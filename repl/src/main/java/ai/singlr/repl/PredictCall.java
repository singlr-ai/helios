/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

/**
 * One {@code predict()} invocation from inside a sandbox during an RLM run.
 *
 * <p>Recorded by {@link ReplSession} on every {@code predict()} call and exposed via {@link
 * RlmResult#predictCalls()} so downstream evaluators can detect signature calls, count distinct
 * sub-LLM invocations, or correlate calls to specific iterations — without grepping JShell source
 * via {@link ai.singlr.repl.sandbox.ExecutionResult#executedCode()}.
 *
 * @param instructions the {@code instructions} arg the model passed to {@code predict()}. Compared
 *     verbatim against registered {@link RequiredPredictSignature}s (subject to the configured
 *     {@link java.util.function.BiPredicate signatureMatcher})
 * @param input the {@code input} arg — typically the slice of context the model wanted the sub-LLM
 *     to operate on
 * @param iteration 0-indexed turn within {@link ReplSession} when this predict fired. Equals {@code
 *     session.history().size()} at the moment of the call: turn 0 is the harness's binding-snippet
 *     execute (when one runs), turn 1 is the model's first {@code execute_code}, and so on. Useful
 *     for plotting predict density across the trajectory or correlating with {@link
 *     RlmResult#history()} entries
 */
public record PredictCall(String instructions, String input, int iteration) {

  public PredictCall {
    if (instructions == null) {
      instructions = "";
    }
    if (input == null) {
      input = "";
    }
  }
}
