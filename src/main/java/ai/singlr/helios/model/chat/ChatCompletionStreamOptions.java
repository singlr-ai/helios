/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;


/**
  * Options for streaming response. Only set this when you set `stream: true`. 
 **/
public record ChatCompletionStreamOptions(Boolean includeUsage) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionStreamOptions options) {
    return new Builder(options);
  }

  public static class Builder {
    private Boolean includeUsage;

    private Builder() {}

    private Builder(ChatCompletionStreamOptions options) {
      this.includeUsage = options.includeUsage;
    }

    public Builder withIncludeUsage(Boolean includeUsage) {
      this.includeUsage = includeUsage;
      return this;
    }

    public ChatCompletionStreamOptions build() {
      return new ChatCompletionStreamOptions(includeUsage);
    }
  }
}
