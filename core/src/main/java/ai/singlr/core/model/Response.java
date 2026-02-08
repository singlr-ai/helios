/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Map;

/**
 * Response from the model.
 *
 * @param <T> the type of parsed structured output (Void for unstructured responses)
 * @param content the text content of the response
 * @param parsed the parsed structured output (null for unstructured responses)
 * @param toolCalls tool calls requested by the model
 * @param finishReason why the model stopped generating
 * @param usage token usage statistics (optional)
 * @param thinking reasoning trace from extended thinking models (optional)
 * @param citations source citations for RAG responses (optional)
 * @param metadata provider-specific data for round-tripping (e.g., thought signatures)
 */
public record Response<T>(
    String content,
    T parsed,
    List<ToolCall> toolCalls,
    FinishReason finishReason,
    Usage usage,
    String thinking,
    List<Citation> citations,
    Map<String, String> metadata) {

  public static Builder<Void> newBuilder() {
    return new Builder<>();
  }

  public static <T> Builder<T> newBuilder(Class<T> type) {
    return new Builder<>();
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  public boolean hasThinking() {
    return thinking != null && !thinking.isEmpty();
  }

  public boolean hasCitations() {
    return citations != null && !citations.isEmpty();
  }

  public boolean hasParsed() {
    return parsed != null;
  }

  public Message toMessage() {
    return Message.assistant(content, toolCalls, metadata);
  }

  /** Token usage statistics. */
  public record Usage(int inputTokens, int outputTokens, int totalTokens) {
    public static Usage of(int input, int output) {
      return new Usage(input, output, input + output);
    }
  }

  public static class Builder<T> {
    private String content;
    private T parsed;
    private List<ToolCall> toolCalls = List.of();
    private FinishReason finishReason;
    private Usage usage;
    private String thinking;
    private List<Citation> citations = List.of();
    private Map<String, String> metadata = Map.of();

    private Builder() {}

    public Builder<T> withContent(String content) {
      this.content = content;
      return this;
    }

    public Builder<T> withParsed(T parsed) {
      this.parsed = parsed;
      return this;
    }

    public Builder<T> withToolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
      return this;
    }

    public Builder<T> withFinishReason(FinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder<T> withUsage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public Builder<T> withThinking(String thinking) {
      this.thinking = thinking;
      return this;
    }

    public Builder<T> withCitations(List<Citation> citations) {
      this.citations = citations != null ? List.copyOf(citations) : List.of();
      return this;
    }

    public Builder<T> withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? metadata : Map.of();
      return this;
    }

    public Response<T> build() {
      return new Response<>(
          content, parsed, toolCalls, finishReason, usage, thinking, citations, metadata);
    }
  }
}
