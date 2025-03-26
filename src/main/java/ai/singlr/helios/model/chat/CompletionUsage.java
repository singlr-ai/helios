/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Usage statistics for the completion request.
 **/
public record CompletionUsage(
    @JsonProperty("completion_tokens")
    Integer completionTokens,
    @JsonProperty("prompt_tokens")
    Integer promptTokens,
    @JsonProperty("total_tokens")
    Integer totalTokens,
    @JsonProperty("completion_tokens_details")
    CompletionTokensDetails completionTokensDetails,
    @JsonProperty("prompt_tokens_details")
    PromptTokensDetails promptTokensDetails
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(CompletionUsage completionUsage) {
    return new Builder(completionUsage);
  }

  public static class Builder {
    private Integer completionTokens;
    private Integer promptTokens;
    private Integer totalTokens;
    private CompletionTokensDetails completionTokensDetails;
    private PromptTokensDetails promptTokensDetails;

    private Builder() {}

    private Builder(CompletionUsage completionUsage) {
      this.completionTokens = completionUsage.completionTokens;
      this.promptTokens = completionUsage.promptTokens;
      this.totalTokens = completionUsage.totalTokens;
      this.completionTokensDetails = completionUsage.completionTokensDetails;
      this.promptTokensDetails = completionUsage.promptTokensDetails;
    }

    public Builder withCompletionTokens(Integer completionTokens) {
      this.completionTokens = completionTokens;
      return this;
    }

    public Builder withPromptTokens(Integer promptTokens) {
      this.promptTokens = promptTokens;
      return this;
    }

    public Builder withTotalTokens(Integer totalTokens) {
      this.totalTokens = totalTokens;
      return this;
    }

    public Builder withCompletionTokensDetails(CompletionTokensDetails completionTokensDetails) {
      this.completionTokensDetails = completionTokensDetails;
      return this;
    }

    public Builder withPromptTokensDetails(PromptTokensDetails promptTokensDetails) {
      this.promptTokensDetails = promptTokensDetails;
      return this;
    }

    public CompletionUsage build() {
      return new CompletionUsage(
          completionTokens,
          promptTokens,
          totalTokens,
          completionTokensDetails,
          promptTokensDetails
      );
    }
  }
}
