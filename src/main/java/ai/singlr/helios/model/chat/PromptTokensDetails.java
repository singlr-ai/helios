/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Breakdown of tokens used in the prompt.
 **/
public record PromptTokensDetails(
    @JsonProperty("audio_tokens")
    Integer audioTokens,
    @JsonProperty("cached_tokens")
    Integer cachedTokens
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(PromptTokensDetails details) {
    return new Builder(details);
  }

  public static class Builder {
    private Integer audioTokens;
    private Integer cachedTokens;

    private Builder() {}

    private Builder(PromptTokensDetails details) {
      this.audioTokens = details.audioTokens;
      this.cachedTokens = details.cachedTokens;
    }

    public Builder withAudioTokens(Integer audioTokens) {
      this.audioTokens = audioTokens;
      return this;
    }

    public Builder withCachedTokens(Integer cachedTokens) {
      this.cachedTokens = cachedTokens;
      return this;
    }

    public PromptTokensDetails build() {
      return new PromptTokensDetails(
          audioTokens,
          cachedTokens
      );
    }
  }
}
