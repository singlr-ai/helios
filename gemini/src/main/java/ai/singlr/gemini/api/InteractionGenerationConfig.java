/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generation configuration for the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>{@code tool_choice} lives here per the spec, not at the top level of the request body.
 *
 * @param temperature sampling temperature (0.0 to 2.0)
 * @param maxOutputTokens maximum number of tokens to generate
 * @param topP nucleus sampling parameter
 * @param topK top-k sampling parameter
 * @param stopSequences sequences that stop generation
 * @param seed random seed for reproducibility
 * @param thinkingLevel thinking/reasoning level ({@code none}, {@code low}, {@code medium}, {@code
 *     high})
 * @param toolChoice tool-choice policy (bare string {@code auto}/{@code any}/{@code none}, or an
 *     allowed-tools restriction)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InteractionGenerationConfig(
    Double temperature,
    @JsonProperty("max_output_tokens") Integer maxOutputTokens,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("top_k") Integer topK,
    @JsonProperty("stop_sequences") List<String> stopSequences,
    Long seed,
    @JsonProperty("thinking_level") String thinkingLevel,
    @JsonProperty("tool_choice") ToolChoiceConfig toolChoice) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private Double temperature;
    private Integer maxOutputTokens;
    private Double topP;
    private Integer topK;
    private List<String> stopSequences;
    private Long seed;
    private String thinkingLevel;
    private ToolChoiceConfig toolChoice;

    private Builder() {}

    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withMaxOutputTokens(Integer maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    public Builder withTopK(Integer topK) {
      this.topK = topK;
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

    public Builder withThinkingLevel(String thinkingLevel) {
      this.thinkingLevel = thinkingLevel;
      return this;
    }

    public Builder withToolChoice(ToolChoiceConfig toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public InteractionGenerationConfig build() {
      return new InteractionGenerationConfig(
          temperature, maxOutputTokens, topP, topK, stopSequences, seed, thinkingLevel, toolChoice);
    }
  }
}
