/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import ai.singlr.core.common.Strings;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxPrelude;
import java.util.List;

/**
 * Builds the canonical system prompt for a {@link CodeActHarness} run.
 *
 * <p>Deliberately narrower than {@link RlmSystemPrompt}: no sub-LM, no predict budget paragraph,
 * and the model is never told about {@code submit()} (because there isn't one here — mentioning it
 * just to negate it primes the model to reach for it). The model writes Java code in a stateful
 * JShell REPL across multiple iterations, then returns its structured answer directly as the final
 * assistant message — the Agent's structured-output path captures it, the sandbox is pure scratch
 * space.
 *
 * <p>The shorter prompt is itself a feature: less context overhead per turn matters on heavy
 * trajectories. SRLM Table 1 shows the REPL-over-externalized-context mechanism is responsible for
 * the bulk of accuracy gains over base models; the sub-LM recursion is a smaller, separable lever.
 * This prompt keeps only the REPL teaching.
 */
public final class CodeActSystemPrompt {

  private CodeActSystemPrompt() {}

  /**
   * Build a CodeAct system prompt from a strategy + input/output schemas + the host functions
   * reachable inside the sandbox.
   *
   * @param strategy task-specific instructions written by the harness user (typically a paragraph
   *     or a short bullet list)
   * @param inputSchema schema describing the input record fields, used to introduce them as bound
   *     REPL variables
   * @param outputSchema schema describing the structured final assistant response the Agent will
   *     parse from the model's last message
   * @param extraHostFunctions any host functions registered for this run, so the prompt enumerates
   *     the full sandbox API. May be empty
   * @param maxOutputCharsToModel cap from {@link ReplConfig#maxOutputCharsToModel()} so the prompt
   *     teaches the truncation discipline accurately
   * @param boundFieldNames names of input fields that are pre-bound as JShell variables. When
   *     non-empty, the prompt tells the model the variables are ready to use; when empty, the
   *     prompt instructs the model to read values from the JSON in the user message
   * @return the assembled system prompt
   */
  public static String build(
      String strategy,
      OutputSchema<?> inputSchema,
      OutputSchema<?> outputSchema,
      List<HostFunction> extraHostFunctions,
      int maxOutputCharsToModel,
      List<String> boundFieldNames) {
    if (outputSchema == null) {
      throw new IllegalArgumentException("outputSchema is required");
    }
    var sb = new StringBuilder();

    sb.append(
        """
        You solve the task by writing Java code in a sandboxed JShell REPL running on JDK 25. The \
        REPL is stateful across turns — variables and methods you declare on one turn are visible \
        on the next.

        The sandbox is restricted to:
        - The JDK 25 standard library (java.util, java.util.stream, java.io, java.math, \
        java.time, etc.)
        - The sandbox conveniences listed below (free print/println/printf plus a few script-style \
        helpers)
        - The host functions listed below under "## Your tools"

        No third-party libraries are available. No filesystem, network, or subprocess access \
        except through the host functions.

        Your final assistant message itself IS the answer — return a JSON object matching the \
        "Required output schema" below. The sandbox is scratch space; the assistant message is \
        the deliverable.

        """);

    if (!Strings.isBlank(strategy)) {
      sb.append("## Task strategy\n").append(strategy.strip()).append("\n\n");
    }

    sb.append("## Input\n");
    if (boundFieldNames != null && !boundFieldNames.isEmpty()) {
      sb.append(
          "These input fields are already bound as JShell variables in your sandbox. Use them"
              + " directly — no need to parse JSON or copy values. The variables are:\n");
      PromptRendering.appendFields(sb, inputSchema);
      sb.append('\n');
      sb.append(
          "(The same JSON is also delivered as the user message for your reference, but the"
              + " variables above are the canonical source — read them.)\n");
    } else {
      sb.append(
          "The user message in the next turn is a JSON document with these fields. The values are"
              + " not pre-bound as JShell variables — read each one as a literal from the user"
              + " message in your first execute_code call.\n");
      PromptRendering.appendFields(sb, inputSchema);
    }
    sb.append('\n');

    sb.append("## Required output schema\n");
    sb.append(
        "Your final assistant message must be a JSON object with these fields. The Agent parses "
            + "it against the schema; if parsing fails you will be re-prompted and can correct it:\n");
    PromptRendering.appendFields(sb, outputSchema);
    sb.append('\n');

    sb.append("## Sandbox conveniences\n")
        .append(SandboxPrelude.modelFacingSummary())
        .append("\n\n");

    sb.append("## Your tools\n");
    sb.append(
        """
        - execute_code(code) — run Java/JShell code. State persists across calls.
        """);
    PromptRendering.appendCustomHostFunctions(sb, extraHostFunctions);
    sb.append('\n');

    sb.append(
        """
        ## How to work
        1. Explore first. On your first iteration, print samples of the inputs to confirm types \
        and shapes. Don't extract before you've looked.
        2. Persist intermediate work in JShell variables. Variables live across iterations; \
        printed output does not. If you need a value later, save it to a named variable.
        """);
    sb.append("3. Printed output you see in tool results is truncated to ~")
        .append(maxOutputCharsToModel)
        .append(
            " characters. The variables themselves retain their full values. If you want to \"see\""
                + " a long value, slice it (e.g. var.substring(0, 500)) — don't rely on seeing the"
                + " full print output.\n");
    sb.append(
        """
        4. When you have the answer, stop calling execute_code and return it. Your final \
        assistant message must be a JSON object matching the output schema above. Printing the \
        answer in the sandbox is NOT the answer — your assistant message IS the answer; the \
        system reads it directly.
        """);

    return sb.toString().strip();
  }
}
