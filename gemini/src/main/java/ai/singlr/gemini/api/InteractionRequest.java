/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for the Gemini Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>The legacy {@code response_mime_type} field is removed; structured output is requested
 * exclusively through the polymorphic {@link ResponseFormat} on {@link #responseFormat()}.
 *
 * @param model the model identifier (e.g., {@code gemini-3-flash})
 * @param input conversation turns
 * @param previousInteractionId interaction ID to continue from (enables stateful continuation)
 * @param systemInstruction system-level guidance for the model
 * @param tools available tools for function calling
 * @param toolChoice controls how the model uses tools
 * @param generationConfig generation parameters
 * @param responseFormat polymorphic response-format selector (text/JSON/image)
 * @param stream whether to stream the response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InteractionRequest(
    String model,
    List<Turn> input,
    @JsonProperty("previous_interaction_id") String previousInteractionId,
    @JsonProperty("system_instruction") String systemInstruction,
    List<ToolDefinition> tools,
    @JsonProperty("tool_choice") ToolChoiceConfig toolChoice,
    @JsonProperty("generation_config") InteractionGenerationConfig generationConfig,
    @JsonProperty("response_format") ResponseFormat responseFormat,
    Boolean stream) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String model;
    private List<Turn> input;
    private String previousInteractionId;
    private String systemInstruction;
    private List<ToolDefinition> tools;
    private ToolChoiceConfig toolChoice;
    private InteractionGenerationConfig generationConfig;
    private ResponseFormat responseFormat;
    private Boolean stream;

    private Builder() {}

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withInput(List<Turn> input) {
      this.input = input;
      return this;
    }

    public Builder withPreviousInteractionId(String previousInteractionId) {
      this.previousInteractionId = previousInteractionId;
      return this;
    }

    public Builder withSystemInstruction(String systemInstruction) {
      this.systemInstruction = systemInstruction;
      return this;
    }

    public Builder withTools(List<ToolDefinition> tools) {
      this.tools = tools;
      return this;
    }

    public Builder withToolChoice(ToolChoiceConfig toolChoice) {
      this.toolChoice = toolChoice;
      return this;
    }

    public Builder withGenerationConfig(InteractionGenerationConfig generationConfig) {
      this.generationConfig = generationConfig;
      return this;
    }

    public Builder withResponseFormat(ResponseFormat responseFormat) {
      this.responseFormat = responseFormat;
      return this;
    }

    public Builder withStream(Boolean stream) {
      this.stream = stream;
      return this;
    }

    public InteractionRequest build() {
      return new InteractionRequest(
          model,
          input,
          previousInteractionId,
          systemInstruction,
          tools,
          toolChoice,
          generationConfig,
          responseFormat,
          stream);
    }
  }
}
