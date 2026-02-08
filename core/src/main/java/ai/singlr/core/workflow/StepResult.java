/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import java.util.Map;

/**
 * The output of a step execution.
 *
 * @param name the step name that produced this result
 * @param content the primary text output
 * @param data key-value metadata produced by the step
 * @param success whether the step completed successfully
 * @param error error message, or null if the step succeeded
 */
public record StepResult(
    String name, String content, Map<String, String> data, boolean success, String error) {

  /** Creates a successful result with content only. */
  public static StepResult success(String name, String content) {
    return new StepResult(name, content, Map.of(), true, null);
  }

  /** Creates a successful result with content and data. */
  public static StepResult success(String name, String content, Map<String, String> data) {
    return new StepResult(name, content, Map.copyOf(data), true, null);
  }

  /** Creates a failure result. */
  public static StepResult failure(String name, String error) {
    return new StepResult(name, null, Map.of(), false, error);
  }

  /** Creates a skip result for branches not taken. */
  public static StepResult skip(String name) {
    return new StepResult(name, null, Map.of(), true, null);
  }
}
