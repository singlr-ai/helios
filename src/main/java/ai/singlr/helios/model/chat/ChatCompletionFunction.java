/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

import java.util.Map;

public record ChatCompletionFunction(
    String description,
    String name,
    Map<String, Object> parameters
) {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ChatCompletionFunction functions) {
    return new Builder(functions);
  }

  public static class Builder {
    private String description;
    private String name;
    private Map<String, Object> parameters;

    private Builder() {}

    private Builder(ChatCompletionFunction functions) {
      this.description = functions.description;
      this.name = functions.name;
      this.parameters = functions.parameters;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withParameters(Map<String, Object> parameters) {
      this.parameters = parameters;
      return this;
    }

    public Builder putParametersItem(String key, Object parametersItem) {
      this.parameters.put(key, parametersItem);
      return this;
    }

    public ChatCompletionFunction build() {
      return new ChatCompletionFunction(description, name, parameters);
    }
  }
}
