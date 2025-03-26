/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.literal;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Literal {
  ASSISTANT("assistant"),
  CHAT_COMPLETION("chat.completion"),
  CONTENT("content"),
  FUNCTION("function");

  private final String value;

  Literal(String value) {
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