/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;

/**
 * Factory for creating the {@code execute_code} tool backed by a {@link ReplSession}. Follows the
 * {@link ai.singlr.core.memory.MemoryTools} and {@code Agent.asTool()} factory patterns.
 *
 * <p>Sandbox exceptions are returned as {@link ToolResult#success} so the model sees tracebacks and
 * can self-correct, rather than {@link ToolResult#failure} which signals framework-level errors.
 */
public final class CodeExecutionTool {

  private CodeExecutionTool() {}

  /**
   * Create an {@code execute_code} tool bound to the given session.
   *
   * @param session the REPL session to execute code in
   * @return a tool that accepts a {@code code} parameter
   */
  public static Tool create(ReplSession session) {
    if (session == null) {
      throw new IllegalArgumentException("Session must not be null");
    }
    return Tool.newBuilder()
        .withName("execute_code")
        .withDescription(
            """
            Execute code in a sandboxed environment. State persists across calls within \
            the same session. Use print() for output. Available host functions: \
            predict(instructions, input) for sub-LM calls with fresh context, \
            submit(output) to signal the final result.""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("code")
                .withType(ParameterType.STRING)
                .withDescription("The code to execute")
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              var code = args.get("code");
              if (!(code instanceof String codeStr) || codeStr.isBlank()) {
                return ToolResult.failure(
                    "Parameter 'code' is required and must be a non-blank string");
              }
              try {
                var result = session.execute(codeStr);
                var output = formatOutput(result);
                return ToolResult.success(output);
              } catch (ReplException e) {
                return ToolResult.success("Error: " + e.getMessage());
              }
            })
        .build();
  }

  private static String formatOutput(ai.singlr.repl.sandbox.ExecutionResult result) {
    var sb = new StringBuilder();
    if (!result.stdout().isEmpty()) {
      sb.append(result.stdout());
    }
    if (!result.stderr().isEmpty()) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("STDERR: ").append(result.stderr());
    }
    if (result.exitCode() != 0) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("[exit code ").append(result.exitCode()).append(']');
    }
    if (result.hasSubmittedValue()) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("[submitted: ").append(result.submitted()).append(']');
    }
    return sb.isEmpty() ? "(no output)" : sb.toString();
  }
}
