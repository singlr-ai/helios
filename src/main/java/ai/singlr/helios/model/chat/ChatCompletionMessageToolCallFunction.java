/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

/**
  * The function that the model called.
 **/
public record ChatCompletionMessageToolCallFunction(
    String name,
    String arguments
) {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionMessageToolCallFunction function) {
    return new Builder(function);
  }

  public static class Builder {
    private String name;
    private String arguments;

    private Builder() {}

    private Builder(ChatCompletionMessageToolCallFunction function) {
      this.name = function.name;
      this.arguments = function.arguments;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withArguments(String arguments) {
      this.arguments = arguments;
      return this;
    }

    public ChatCompletionMessageToolCallFunction build() {
      return new ChatCompletionMessageToolCallFunction(name, arguments);
    }
  }
}
