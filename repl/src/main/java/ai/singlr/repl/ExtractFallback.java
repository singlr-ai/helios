/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.common.Result;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;

/**
 * Reconstitute a structured output from a trajectory when the agent loop ended without {@code
 * submit()} being called.
 *
 * <p>Implements the paper's recovery for the Appendix B.2 failure mode (the model builds the right
 * answer in REPL variables but never returns it). Trampoline calls this {@code _extract_fallback};
 * we follow the same shape: a single, fresh, schema-constrained model call against a textual
 * trajectory summary, no tools, no memory, no recursive {@code predict}.
 *
 * <p>Typical usage from a harness:
 *
 * <pre>{@code
 * if (session.submittedOutput() == null && config.submitSchema() != null) {
 *   var summary = ExtractFallback.summarize(agentMessageHistory);
 *   var fallback = ExtractFallback.attempt(model, schema, summary);
 *   // surface fallback.parsed() to the caller with status=extracted
 * }
 * }</pre>
 *
 * <p>Intentionally minimal: no streaming, no memory, no tool use. The fallback is a one-shot
 * structured-extraction call.
 */
public final class ExtractFallback {

  private ExtractFallback() {}

  /**
   * System prompt for the extract-fallback agent. Model-facing instructions for reconstituting
   * structured output from a trajectory when the run timed out without an explicit submit.
   */
  static final String EXTRACT_SYSTEM_PROMPT =
      """
      Your earlier execution ran to completion of its iteration budget without calling \
      submit(). The trajectory below shows what you did. Return the final structured output \
      now, conforming exactly to the schema. Use the trajectory to recover the values that \
      were already computed; do not invent new work or call any tools.
      """;

  /**
   * Run a one-shot extract-fallback model call.
   *
   * @param model the model to use for the extract call
   * @param schema the output schema to enforce on the response
   * @param trajectorySummary a textual summary of the trajectory so far, suitable for the model to
   *     read and reconstitute the output from. The {@link #summarize(java.util.List)} helper
   *     produces a sensible default
   * @param <T> the parsed output type
   * @return a {@link Result} carrying the parsed output on success, or a failure describing why
   *     extraction did not produce a typed value
   */
  public static <T> Result<T> attempt(
      Model model, OutputSchema<T> schema, String trajectorySummary) {
    if (model == null) {
      return Result.failure("model must not be null");
    }
    if (schema == null) {
      return Result.failure("schema must not be null");
    }
    if (trajectorySummary == null || trajectorySummary.isBlank()) {
      return Result.failure("trajectorySummary must not be null or blank");
    }
    var config =
        AgentConfig.newBuilder()
            .withName("extract-fallback")
            .withModel(model)
            .withSystemPrompt(EXTRACT_SYSTEM_PROMPT)
            .withIncludeMemoryTools(false)
            .withMaxIterations(1)
            .build();
    var agent = new Agent(config);
    var result = agent.run(SessionContext.of(trajectorySummary), schema);
    return switch (result) {
      case Result.Success<?> s -> {
        @SuppressWarnings("unchecked")
        var response = (ai.singlr.core.model.Response<T>) s.value();
        if (response.parsed() == null) {
          yield Result.failure(
              "extract-fallback returned no parsed value (model may have produced free-form text "
                  + "instead of structured output)");
        }
        yield Result.success(response.parsed());
      }
      case Result.Failure<?> f -> Result.failure("extract-fallback failed: " + f.error());
    };
  }

  /**
   * Default trajectory formatter: render an agent's message history as a human-readable sequence of
   * code/output pairs the model can read back.
   *
   * <p>The formatter walks the message list extracting tool-call arguments (treated as the {@code
   * code} the model emitted) paired with subsequent tool-result messages. Plain assistant text is
   * rendered as a "thought" line. The result is grouped into iterations.
   *
   * @param messages the agent's message history
   * @return a multi-line trajectory summary, or an empty string if there are no relevant messages
   */
  public static String summarize(java.util.List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return "";
    }
    var sb = new StringBuilder();
    int iteration = 0;
    var pendingToolCalls = new java.util.LinkedHashMap<String, String>();
    for (var msg : messages) {
      if (msg.role() == ai.singlr.core.model.Role.ASSISTANT) {
        if (msg.hasToolCalls()) {
          iteration++;
          sb.append("\n--- Iteration ").append(iteration).append(" ---\n");
          if (msg.content() != null && !msg.content().isBlank()) {
            sb.append("Reasoning: ").append(msg.content().strip()).append('\n');
          }
          for (var call : msg.toolCalls()) {
            var args =
                call.arguments() != null
                    ? call.arguments().getOrDefault("code", call.arguments())
                    : "";
            sb.append("Code (").append(call.name()).append("):\n").append(args).append('\n');
            pendingToolCalls.put(call.id(), call.name());
          }
        } else if (msg.content() != null && !msg.content().isBlank()) {
          sb.append("\nFinal thought: ").append(msg.content().strip()).append('\n');
        }
      } else if (msg.role() == ai.singlr.core.model.Role.TOOL) {
        if (msg.toolCallId() != null) {
          pendingToolCalls.remove(msg.toolCallId());
        }
        sb.append("Output:\n").append(msg.content() == null ? "" : msg.content()).append('\n');
      }
    }
    return sb.toString().strip();
  }
}
