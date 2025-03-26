/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.Literal;

public record ChatCompletionMessageToolCall(
    String id,
    Literal type,
    ChatCompletionMessageToolCallFunction function
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionMessageToolCall toolCall) {
    return new Builder(toolCall);
  }

  public static class Builder {
    private String id;
    private Literal type;
    private ChatCompletionMessageToolCallFunction function;

    private Builder() {}

    private Builder(ChatCompletionMessageToolCall toolCall) {
      this.id = toolCall.id;
      this.type = toolCall.type;
      this.function = toolCall.function;
    }

    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    public Builder withType(Literal type) {
      this.type = type;
      return this;
    }

    public Builder withFunction(ChatCompletionMessageToolCallFunction function) {
      this.function = function;
      return this;
    }

    public ChatCompletionMessageToolCall build() {
      return new ChatCompletionMessageToolCall(id, type, function);
    }
  }
}
