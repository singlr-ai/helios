/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.Literal;

public record ChatCompletionPrediction(Literal type, String content) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionPrediction prediction) {
    return new Builder(prediction);
  }

  public static class Builder {
    private Literal type = Literal.CONTENT;
    private String content;

    private Builder() {}

    private Builder(ChatCompletionPrediction prediction) {
      this.type = prediction.type;
      this.content = prediction.content;
    }

    public Builder withType(Literal type) {
      this.type = type;
      return this;
    }

    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    public ChatCompletionPrediction build() {
      return new ChatCompletionPrediction(type, content);
    }
  }
}
