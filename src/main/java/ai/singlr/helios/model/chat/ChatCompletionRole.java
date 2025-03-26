/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The role of the author of a message
 */
public enum ChatCompletionRole {
  
  SYSTEM("system"),
  
  USER("user"),
  
  ASSISTANT("assistant"),
  
  TOOL("tool"),
  
  FUNCTION("function");

  private final String value;

  ChatCompletionRole(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }

  @JsonCreator
  public static ChatCompletionRole fromValue(String text) {
    for (ChatCompletionRole b : ChatCompletionRole.values()) {
      if (String.valueOf(b.value).equals(text)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + text + "'");
  }
}
