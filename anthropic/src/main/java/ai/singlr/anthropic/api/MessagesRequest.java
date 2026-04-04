/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for the Claude Messages API.
 *
 * @param model the model identifier
 * @param maxTokens maximum tokens to generate (required by Claude)
 * @param messages conversation messages
 * @param system system prompt (extracted from messages)
 * @param stream whether to stream the response
 * @param tools available tools for function calling
 * @param toolChoice controls how the model uses tools
 * @param temperature controls randomness
 * @param topP nucleus sampling threshold
 * @param stopSequences sequences that stop generation
 * @param thinking extended thinking configuration
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessagesRequest(
    String model,
    @JsonProperty("max_tokens") Integer maxTokens,
    List<MessageEntry> messages,
    String system,
    Boolean stream,
    List<ToolDefinition> tools,
    @JsonProperty("tool_choice") ToolChoiceConfig toolChoice,
    Double temperature,
    @JsonProperty("top_p") Double topP,
    @JsonProperty("stop_sequences") List<String> stopSequences,
    ThinkingConfig thinking) {

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A message entry in the conversation.
   *
   * @param role "user" or "assistant"
   * @param content text string or list of content blocks
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MessageEntry(String role, Object content) {

    public static MessageEntry user(String text) {
      return new MessageEntry("user", text);
    }

    public static MessageEntry user(List<ContentBlock> blocks) {
      return new MessageEntry("user", blocks);
    }

    public static MessageEntry assistant(String text) {
      return new MessageEntry("assistant", text);
    }

    public static MessageEntry assistant(List<ContentBlock> blocks) {
      return new MessageEntry("assistant", blocks);
    }
  }

  public static class Builder {
    private String model;
    private Integer maxTokens;
    private List<MessageEntry> messages;
    private String system;
    private Boolean stream;
    private List<ToolDefinition> tools;
    private ToolChoiceConfig toolChoice;
    private Double temperature;
    private Double topP;
    private List<String> stopSequences;
    private ThinkingConfig thinking;

    private Builder() {}

    public Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public Builder withMaxTokens(Integer maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public Builder withMessages(List<MessageEntry> messages) {
      this.messages = messages;
      return this;
    }

    public Builder withSystem(String system) {
      this.system = system;
      return this;
    }

    public Builder withStream(Boolean stream) {
      this.stream = stream;
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

    public Builder withTemperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder withTopP(Double topP) {
      this.topP = topP;
      return this;
    }

    public Builder withStopSequences(List<String> stopSequences) {
      this.stopSequences = stopSequences;
      return this;
    }

    public Builder withThinking(ThinkingConfig thinking) {
      this.thinking = thinking;
      return this;
    }

    public MessagesRequest build() {
      return new MessagesRequest(
          model,
          maxTokens,
          messages,
          system,
          stream,
          tools,
          toolChoice,
          temperature,
          topP,
          stopSequences,
          thinking);
    }
  }
}
