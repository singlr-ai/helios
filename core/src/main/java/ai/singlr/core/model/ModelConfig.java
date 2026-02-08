/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for model providers.
 *
 * <p>Contains provider-agnostic settings like API credentials, HTTP timeouts, and generation
 * parameters.
 *
 * @param apiKey the API key for authentication
 * @param thinkingLevel level of reasoning trace to include in responses
 * @param connectTimeout maximum time to establish HTTP connection
 * @param responseTimeout maximum time to wait for HTTP response headers
 * @param temperature controls randomness (0.0 = deterministic, 2.0 = very random)
 * @param topP nucleus sampling threshold (0.0-1.0)
 * @param maxOutputTokens maximum tokens to generate
 * @param stopSequences sequences that stop generation
 * @param seed random seed for reproducibility
 * @param toolChoice controls how the model uses tools
 */
public record ModelConfig(
    String apiKey,
    ThinkingLevel thinkingLevel,
    Duration connectTimeout,
    Duration responseTimeout,
    Double temperature,
    Double topP,
    Integer maxOutputTokens,
    List<String> stopSequences,
    Long seed,
    ToolChoice toolChoice) {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(60);

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ModelConfig config) {
    return new Builder(config);
  }

  public static ModelConfig of(String apiKey) {
    return new Builder().withApiKey(apiKey).build();
  }

  public static class Builder {
    private String apiKey;
    private ThinkingLevel thinkingLevel = ThinkingLevel.NONE;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private List<String> stopSequences;
    private Long seed;
    private ToolChoice toolChoice;

    private Builder() {}

    private Builder(ModelConfig config) {
      this.apiKey = config.apiKey;
      this.thinkingLevel = config.thinkingLevel;
      this.connectTimeout = config.connectTimeout;
      this.responseTimeout = config.responseTimeout;
      this.temperature = config.temperature;
      this.topP = config.topP;
      this.maxOutputTokens = config.maxOutputTokens;
      this.stopSequences = config.stopSequences;
      this.seed = config.seed;
      this.toolChoice = config.toolChoice;
    }

    public Builder withApiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder withThinkingLevel(ThinkingLevel thinkingLevel) {
      this.thinkingLevel = thinkingLevel;
      return this;
    }

    public Builder withConnectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder withResponseTimeout(Duration responseTimeout) {
      this.responseTimeout = responseTimeout;
      return this;
    }

    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    public Builder withSeed(Long seed) {
      this.seed = seed;
      return this;
    }

    public Builder withToolChoice(ToolChoice toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public ModelConfig build() {
      return new ModelConfig(
          apiKey,
          thinkingLevel,
          connectTimeout,
          responseTimeout,
          temperature,
          topP,
          maxOutputTokens,
          stopSequences,
          seed,
          toolChoice);
    }
  }
}
