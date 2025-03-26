/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import ai.singlr.helios.model.base.TopLogprob;

import java.util.ArrayList;
import java.util.List;

public record ChatCompletionTokenLogprob(
    String token,
    Float logprob,
    List<Integer> bytes,
    List<TopLogprob> topLogprobs
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionTokenLogprob chatCompletionTokenLogprob) {
    return new Builder(chatCompletionTokenLogprob);
  }

  public static class Builder {
    private String token;
    private Float logprob;
    private List<Integer> bytes;
    private List<TopLogprob> topLogprobs;

    private Builder() {}

    private Builder(ChatCompletionTokenLogprob chatCompletionTokenLogprob) {
      this.token = chatCompletionTokenLogprob.token;
      this.logprob = chatCompletionTokenLogprob.logprob;
      this.bytes = chatCompletionTokenLogprob.bytes;
      this.topLogprobs = chatCompletionTokenLogprob.topLogprobs;
    }

    public Builder withToken(String token) {
      this.token = token;
      return this;
    }

    public Builder withLogprob(Float logprob) {
      this.logprob = logprob;
      return this;
    }

    public Builder withBytes(List<Integer> bytes) {
      this.bytes = bytes;
      return this;
    }

    public Builder withTopLogprobs(List<TopLogprob> topLogprobs) {
      this.topLogprobs = topLogprobs;
      return this;
    }

    public ChatCompletionTokenLogprob build() {
      if (bytes == null) {
        bytes = new ArrayList<>();
      }
      if (topLogprobs == null) {
        topLogprobs = new ArrayList<>();
      }
      return new ChatCompletionTokenLogprob(token, logprob, bytes, topLogprobs);
    }
  }
}
