/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import java.util.Map;

/**
 * Functional interface for tool execution. Arguments come from the model as a Map (parsed from
 * JSON).
 */
@FunctionalInterface
public interface ToolExecutor {

  /**
   * Execute the tool with the given arguments.
   *
   * @param arguments the arguments from the model
   * @return the result of the execution
   */
  ToolResult execute(Map<String, Object> arguments);
}
