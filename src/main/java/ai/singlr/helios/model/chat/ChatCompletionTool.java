/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.Literal;
import ai.singlr.helios.model.base.FunctionObject;

public record ChatCompletionTool(Literal type, FunctionObject function) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionTool tool) {
    return new Builder(tool);
  }

  public static class Builder {
    private Literal type = Literal.FUNCTION;
    private FunctionObject function;

    private Builder() {}

    private Builder(ChatCompletionTool tool) {
      this.type = tool.type;
      this.function = tool.function;
    }

    public Builder withFunction(FunctionObject function) {
      this.function = function;
      return this;
    }

    public ChatCompletionTool build() {
      return new ChatCompletionTool(type, function);
    }
  }
}
