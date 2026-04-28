/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.common.Strings;
import ai.singlr.core.schema.JsonSchema;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxPrelude;
import java.util.List;

/**
 * Builds the canonical system prompt for an RLM (Recursive Language Model) run.
 *
 * <p>Ports the load-bearing conventions from Trampoline's {@code predict-rlm} {@code
 * PREDICT_RLM_INSTRUCTIONS} (~18K characters of accumulated production wisdom) to a Java/JShell
 * idiom. Every paragraph here addresses a real failure mode the paper or downstream users
 * encountered:
 *
 * <ul>
 *   <li>"Variables persist, prints get truncated" — paper Appendix B.2 lost-answer failure
 *   <li>"Verify, then submit alone" — Appendix B.2 again; Trampoline's hardest-won lesson
 *   <li>"Don't loop predict over every line" — Appendix B.3 runaway recursion
 *   <li>"Submit's structured output is enforced" — typed submit is the contract
 *   <li>"Validation errors come back inline; fix and resubmit" — typed submit retry loop
 * </ul>
 *
 * <p>Generated from input/output schemas + a user-supplied strategy docstring. The shape is
 * deliberately Trampoline-like so the prompts transfer if a user is migrating from {@code
 * predict-rlm} or DSPy.
 */
public final class RlmSystemPrompt {

  private RlmSystemPrompt() {}

  /**
   * Build a system prompt from a strategy + input/output schemas + the host functions reachable
   * inside the sandbox.
   *
   * @param strategy task-specific instructions written by the harness user (typically a paragraph
   *     or a short bullet list)
   * @param inputSchema schema describing the input record fields, used to introduce them as bound
   *     REPL variables
   * @param outputSchema schema describing the structured output the model must {@code submit()}
   * @param extraHostFunctions any host functions beyond the auto-installed {@code predict} / {@code
   *     submit} pair, so the prompt enumerates the full sandbox API. May be empty
   * @param maxOutputCharsToModel cap from {@link ReplConfig#maxOutputCharsToModel()} so the prompt
   *     teaches the truncation discipline accurately
   * @param maxLlmCalls cap from {@link ReplConfig#maxLlmCalls()} so the prompt sets the budget
   *     expectation. Pass {@code 0} to omit the budget paragraph
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
      int maxLlmCalls,
      List<String> boundFieldNames) {
    if (outputSchema == null) {
      throw new IllegalArgumentException("outputSchema is required");
    }
    var sb = new StringBuilder();

    sb.append(
        """
        You solve a long-horizon task by writing Java code in a sandboxed JShell REPL. The REPL \
        runs across many turns; variables you bind on one turn are still there on the next. Your \
        goal is to produce a structured final answer by calling submit(...).

        """);

    if (!Strings.isBlank(strategy)) {
      sb.append("## Task strategy\n").append(strategy.strip()).append("\n\n");
    }

    sb.append("## Input\n");
    if (boundFieldNames != null && !boundFieldNames.isEmpty()) {
      sb.append(
          "These input fields are already bound as JShell variables in your sandbox. Use them"
              + " directly — no need to parse JSON or copy values. The variables are:\n");
      appendFields(sb, inputSchema);
      sb.append('\n');
      sb.append(
          "(The same JSON is also delivered as the user message for your reference, but the"
              + " variables above are the canonical source — read them.)\n");
    } else {
      sb.append(
          "The user message in the next turn is a JSON document with these fields. The values are"
              + " not pre-bound as JShell variables — for small inputs, read the values directly"
              + " from the JSON and use them as literals in your JShell code; for larger inputs,"
              + " parse the JSON inside execute_code (Jackson is on the sandbox classpath as"
              + " tools.jackson.databind.json.JsonMapper).\n");
      appendFields(sb, inputSchema);
    }
    sb.append('\n');

    sb.append("## Required output\n");
    sb.append(
        "You signal completion by calling submit(...) with a Map whose keys match this schema. "
            + "The host validates against the schema; if validation fails you will see an error "
            + "in your next iteration's tool result and can fix and resubmit:\n");
    appendFields(sb, outputSchema);
    sb.append('\n');

    sb.append("## Sandbox conveniences\n")
        .append(SandboxPrelude.modelFacingSummary())
        .append("\n\n");

    sb.append("## Your tools\n");
    sb.append(
        """
        - execute_code(code) — run Java/JShell code. State persists across calls.

        Inside the sandbox these host functions are reachable directly:
          - predict(instructions, input) — call a sub-LLM with fresh context. Returns a String. \
        Use this for analysis you can't compute deterministically (summarization, classification, \
        extraction). Each call is independent — no chat memory.
          - submit(output) — submit your final structured result. Output must be a Map matching \
        the output schema above. Calling submit ends the run after the current iteration.
        """);
    if (!extraHostFunctions.isEmpty()) {
      for (var fn : extraHostFunctions) {
        if ("predict".equals(fn.name()) || "submit".equals(fn.name())) {
          continue;
        }
        sb.append("  - ").append(fn.name()).append(" — ").append(fn.description()).append('\n');
      }
    }
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
        4. Use predict() for the parts the LM has to do. predict("instructions", input) returns a \
        String. Capture that string in a variable; do not print large predict() outputs at full \
        length.

        ### CRITICAL: how the run ends

        Your work is captured ONLY when you call submit(...). Computing a value, printing it, or \
        leaving it in a variable does NOT return it. This is NOT a notebook — there is no \
        "implicit last expression" semantics. The loop keeps running until you submit; if you \
        stop calling tools without ever calling submit, the run terminates with no result and \
        your work is lost.

        Therefore, every run MUST end with a call to submit(...). When you have computed the \
        answer, your next execute_code call should be:

            submit(Map.of("field1", value1, "field2", value2, ...))

        and nothing else. Do NOT mix verification and submit in the same execute_code call — \
        verify in one call (print, check), then submit ALONE in the next call. If you only \
        printed the answer and didn't call submit, you are not done.

        If submit fails validation, you will see "Submit validation failed: ..." in the tool \
        result naming the missing or wrong-typed fields. Read the message, fix the Map, and call \
        submit again. Sandbox variables are not lost across validation failures.
        """);
    if (maxLlmCalls > 0) {
      sb.append("7. Budget: you have at most ")
          .append(maxLlmCalls)
          .append(
              " predict() calls per session. If you trip the budget you will see "
                  + "SandboxBudgetExceededException — at that point stop calling predict and submit"
                  + " your best answer with the data you've already gathered.\n");
    }
    sb.append('\n');
    sb.append(
        """
        Be concise in your reasoning text. The work happens in the code blocks; the prose around \
        them should explain what you intend to do and why, not retell the code.
        """);

    return sb.toString().strip();
  }

  private static void appendFields(StringBuilder sb, OutputSchema<?> schema) {
    if (schema == null) {
      return;
    }
    var root = schema.schema();
    if (root == null || root.properties() == null) {
      return;
    }
    var required = root.required() != null ? root.required() : List.<String>of();
    for (var entry : root.properties().entrySet()) {
      var name = entry.getKey();
      var prop = entry.getValue();
      sb.append("  - ").append(name).append(" (").append(describe(prop)).append(")");
      if (!required.contains(name)) {
        sb.append(" [optional]");
      }
      if (!Strings.isBlank(prop.description())) {
        sb.append(" — ").append(prop.description());
      }
      sb.append('\n');
    }
  }

  private static String describe(JsonSchema schema) {
    if (schema == null || schema.type() == null) {
      return "any";
    }
    return switch (schema.type()) {
      case "array" -> "List<" + (schema.items() != null ? describe(schema.items()) : "any") + ">";
      case "object" -> "object";
      case "integer" -> "int";
      case "number" -> "number";
      case "boolean" -> "boolean";
      case "string" ->
          schema.enumValues() != null && !schema.enumValues().isEmpty()
              ? "enum " + schema.enumValues()
              : "String";
      default -> schema.type();
    };
  }
}
