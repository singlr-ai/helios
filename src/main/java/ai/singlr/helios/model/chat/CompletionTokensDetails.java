/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Breakdown of tokens used in a completion.
 **/
public record CompletionTokensDetails(
    @JsonProperty("accepted_prediction_tokens")
    Integer acceptedPredictionTokens,
    @JsonProperty("audio_tokens")
    Integer audioTokens,
    @JsonProperty("reasoning_tokens")
    Integer reasoningTokens,
    @JsonProperty("rejected_prediction_tokens")
    Integer rejectedPredictionTokens
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(CompletionTokensDetails details) {
    return new Builder(details);
  }

  public static class Builder {
    private Integer acceptedPredictionTokens;
    private Integer audioTokens;
    private Integer reasoningTokens;
    private Integer rejectedPredictionTokens;

    private Builder() {}

    private Builder(CompletionTokensDetails details) {
      this.acceptedPredictionTokens = details.acceptedPredictionTokens;
      this.audioTokens = details.audioTokens;
      this.reasoningTokens = details.reasoningTokens;
      this.rejectedPredictionTokens = details.rejectedPredictionTokens;
    }

    public Builder withAcceptedPredictionTokens(Integer acceptedPredictionTokens) {
      this.acceptedPredictionTokens = acceptedPredictionTokens;
      return this;
    }

    public Builder withAudioTokens(Integer audioTokens) {
      this.audioTokens = audioTokens;
      return this;
    }

    public Builder withReasoningTokens(Integer reasoningTokens) {
      this.reasoningTokens = reasoningTokens;
      return this;
    }

    public Builder withRejectedPredictionTokens(Integer rejectedPredictionTokens) {
      this.rejectedPredictionTokens = rejectedPredictionTokens;
      return this;
    }

    public CompletionTokensDetails build() {
      return new CompletionTokensDetails(
          acceptedPredictionTokens,
          audioTokens,
          reasoningTokens,
          rejectedPredictionTokens
      );
    }
  }
}
