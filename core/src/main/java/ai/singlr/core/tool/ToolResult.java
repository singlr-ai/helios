/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

/**
 * Result of a tool execution.
 *
 * @param success whether the execution succeeded
 * @param output the output (result text or error message)
 * @param data optional structured data from the tool
 */
public record ToolResult(boolean success, String output, Object data) {

  public static ToolResult success(String output) {
    return new ToolResult(true, output, null);
  }

  public static ToolResult success(String output, Object data) {
    return new ToolResult(true, output, data);
  }

  public static ToolResult failure(String error) {
    return new ToolResult(false, error, null);
  }

  public static ToolResult failure(String error, Exception cause) {
    var message = error;
    if (cause != null && cause.getMessage() != null) {
      message = error + ": " + cause.getMessage();
    }
    return new ToolResult(false, message, null);
  }
}
