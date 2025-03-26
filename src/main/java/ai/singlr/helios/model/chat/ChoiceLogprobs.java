/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import java.util.List;

/**
  * Log probability information for the choice.
 **/
public record ChoiceLogprobs(
    List<ChatCompletionTokenLogprob> content,
    List<ChatCompletionTokenLogprob> refusal
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChoiceLogprobs choiceLogprobs) {
    return new Builder(choiceLogprobs);
  }

  public static class Builder {
    private List<ChatCompletionTokenLogprob> content;
    private List<ChatCompletionTokenLogprob> refusal;

    private Builder() {}

    private Builder(ChoiceLogprobs choiceLogprobs) {
      this.content = choiceLogprobs.content;
      this.refusal = choiceLogprobs.refusal;
    }

    public Builder withContent(List<ChatCompletionTokenLogprob> content) {
      this.content = content;
      return this;
    }

    public Builder withRefusal(List<ChatCompletionTokenLogprob> refusal) {
      this.refusal = refusal;
      return this;
    }

    public ChoiceLogprobs build() {
      return new ChoiceLogprobs(content, refusal);
    }
  }
}
