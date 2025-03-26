/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.literal.FinishReason;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Choice(
    @JsonProperty("finish_reason")
    FinishReason finishReason,
    Integer index,
    ChatCompletionMessage message,
    ChoiceLogprobs logprobs
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Choice choice) {
    return new Builder(choice);
  }

  public static class Builder {
    private FinishReason finishReason;
    private Integer index;
    private ChatCompletionMessage message;
    private ChoiceLogprobs logprobs;

    private Builder() {}

    private Builder(Choice choice) {
      this.finishReason = choice.finishReason;
      this.index = choice.index;
      this.message = choice.message;
      this.logprobs = choice.logprobs;
    }

    public Builder withFinishReason(FinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder withIndex(Integer index) {
      this.index = index;
      return this;
    }

    public Builder withMessage(ChatCompletionMessage message) {
      this.message = message;
      return this;
    }

    public Builder withLogprobs(ChoiceLogprobs logprobs) {
      this.logprobs = logprobs;
      return this;
    }

    public Choice build() {
      return new Choice(finishReason, index, message, logprobs);
    }
  }
}
