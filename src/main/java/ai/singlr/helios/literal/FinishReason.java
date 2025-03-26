/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FinishReason {

  STOP("stop"),
  LENGTH("length"),
  TOOL_CALLS("tool_calls"),
  CONTENT_FILTER("content_filter"),
  FUNCTION_CALL("function_call");

  private final String value;

  FinishReason(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }
}
