/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** Events emitted during streaming response from a model. */
public sealed interface StreamEvent {

  /**
   * A chunk of text content.
   *
   * @param text the text fragment emitted by the model for this delta
   */
  record TextDelta(String text) implements StreamEvent {}

  /**
   * A tool call has started.
   *
   * @param callId provider-assigned identifier correlating subsequent deltas to this call
   * @param toolName which tool the model is about to invoke
   */
  record ToolCallStart(String callId, String toolName) implements StreamEvent {}

  /**
   * Incremental arguments for an ongoing tool call.
   *
   * @param callId the call id originally surfaced by {@link ToolCallStart}
   * @param argumentsDelta JSON fragment to append to the call's accumulating argument string
   */
  record ToolCallDelta(String callId, String argumentsDelta) implements StreamEvent {}

  /**
   * A tool call is complete.
   *
   * @param toolCall fully-assembled tool invocation ready to dispatch
   */
  record ToolCallComplete(ToolCall toolCall) implements StreamEvent {}

  /**
   * The stream is complete.
   *
   * @param response the final aggregated response (accumulated text, tool calls, usage, finish
   *     reason)
   */
  record Done(Response<?> response) implements StreamEvent {}

  /**
   * An error occurred.
   *
   * @param message human-readable error description
   * @param cause originating exception, when known; may be {@code null}
   */
  record Error(String message, Exception cause) implements StreamEvent {
    /**
     * Convenience constructor when no underlying exception is available.
     *
     * @param message human-readable error description
     */
    public Error(String message) {
      this(message, null);
    }
  }
}
