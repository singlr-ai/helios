/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** Events emitted during streaming response from a model. */
public sealed interface StreamEvent {

  /** A chunk of text content. */
  record TextDelta(String text) implements StreamEvent {}

  /** A tool call has started. */
  record ToolCallStart(String callId, String toolName) implements StreamEvent {}

  /** Incremental arguments for an ongoing tool call. */
  record ToolCallDelta(String callId, String argumentsDelta) implements StreamEvent {}

  /** A tool call is complete. */
  record ToolCallComplete(ToolCall toolCall) implements StreamEvent {}

  /** The stream is complete. */
  record Done(Response<?> response) implements StreamEvent {}

  /** An error occurred. */
  record Error(String message, Exception cause) implements StreamEvent {
    public Error(String message) {
      this(message, null);
    }
  }
}
