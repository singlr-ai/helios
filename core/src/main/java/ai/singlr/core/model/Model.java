/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.Iterator;
import java.util.List;

/**
 * Interface for LLM providers. Implementations provide the actual integration with model APIs
 * (Gemini, Anthropic, etc.).
 */
public interface Model {

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
   * Stream response from the model.
   *
   * @param messages the conversation history
   * @param tools available tools
   * @return iterator of stream events
   */
  default Iterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var response = chat(messages, tools);
    return List.of((StreamEvent) new StreamEvent.Done(response)).iterator();
  }

  /** The model identifier (e.g., "gemini-2.0-flash", "claude-3-opus"). */
  String id();

  /** The provider name (e.g., "gemini", "anthropic", "openai"). */
  String provider();
}
