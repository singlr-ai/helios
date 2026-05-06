/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.List;

/**
 * Interface for LLM providers. Implementations provide the actual integration with model APIs
 * (Gemini, Anthropic, etc.).
 *
 * <p>Models may hold long-lived resources (HTTP connection pools, file descriptors). Long-running
 * hosts that build and discard models should call {@link #close()} to release them. Implementations
 * must make {@code close()} idempotent. The default is a no-op for stateless implementations.
 */
public interface Model extends AutoCloseable {

  /**
   * Send messages to the model and get a response.
   *
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @return the model's response
   */
  Response<Void> chat(List<Message> messages, List<Tool> tools);

  /** Send messages without tools. */
  default Response<Void> chat(List<Message> messages) {
    return chat(messages, List.of());
  }

  /**
   * Send messages with structured output schema.
   *
   * @param <T> the type of the structured output
   * @param messages the conversation history
   * @param tools available tools the model can call
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  default <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    throw new UnsupportedOperationException("Structured output not supported by this model");
  }

  /**
   * Send messages with structured output schema, no tools.
   *
   * @param <T> the type of the structured output
   * @param messages the conversation history
   * @param outputSchema the schema for structured output
   * @return response with parsed structured output
   */
  default <T> Response<T> chat(List<Message> messages, OutputSchema<T> outputSchema) {
    return chat(messages, List.of(), outputSchema);
  }

  /**
   * Stream response from the model. The returned iterator may hold resources (HTTP connections,
   * streams) and should be used in a try-with-resources block to ensure cleanup.
   *
   * @param messages the conversation history
   * @param tools available tools
   * @return closeable iterator of stream events
   */
  default CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var response = chat(messages, tools);
    return CloseableIterator.of(List.of((StreamEvent) new StreamEvent.Done(response)).iterator());
  }

  /** Stream response without tools. */
  default CloseableIterator<StreamEvent> chatStream(List<Message> messages) {
    return chatStream(messages, List.of());
  }

  /** The model identifier (e.g., "gemini-2.0-flash", "claude-3-opus"). */
  String id();

  /** The provider name (e.g., "gemini", "anthropic", "openai"). */
  String provider();

  /** Context window size in tokens. Returns 0 if unknown (compaction disabled). */
  default int contextWindow() {
    return 0;
  }

  /**
   * Maximum output tokens this model produces in a single response. Providers should return their
   * model's documented ceiling so callers that don't set {@link
   * ModelConfig.Builder#withMaxOutputTokens(Integer)} aren't silently truncated at a framework
   * default. Returns 0 if unknown — a {@code 0} fallback means the provider sends whatever the API
   * itself defaults to (or rejects with a 400 if the API requires the field).
   *
   * @return the per-model output ceiling, or {@code 0} when unknown
   */
  default int maxOutputTokens() {
    return 0;
  }

  /**
   * Release resources held by this model. Default no-op. Implementations holding HTTP clients,
   * connection pools, or other OS resources should override and clean up. Must be idempotent —
   * calling {@code close()} more than once must be safe and have no additional effect.
   *
   * <p>A {@code Model} is typically shared across many {@link ai.singlr.core.agent.Agent Agent}
   * instances (Agent is per-request and does not own the Model). The component that constructs the
   * Model owns its lifecycle and is responsible for calling {@code close()} once at application
   * shutdown — Agent itself does not close its Model on completion. Closing a Model while other
   * Agents reference it will fail those Agents' subsequent requests.
   */
  @Override
  default void close() {}
}
