/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.common.Strings;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Message;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-run fan-out of {@link HeliosEvent}s to a collection of {@link EventSink}s. Created fresh per
 * {@code runLoop} / {@code streamLoop} invocation so each run has its own stable {@link UUID}
 * {@code runId} (UUID v7 from {@code Ids.newId()}).
 *
 * <p>Sink exceptions are caught and logged at {@code WARNING} — a misbehaving consumer never aborts
 * the agent loop, matching the contract documented on {@link EventSink#onEvent}.
 *
 * <p>Span lifecycle events ({@code SpanOpened} / {@code SpanClosed}) are emitted directly by {@code
 * SpanBuilder} to {@code config.eventSinks()}, not through this emitter. This class only covers
 * run-level, iteration-level, content-level, and tool-level events plus the agent-loop hooks
 * ({@code BeforeApiCall}, {@code AfterTurn}, {@code BeforeCompaction}, {@code SessionEnd}).
 */
final class EventEmitter {

  private static final Logger LOG = Logger.getLogger(EventEmitter.class.getName());

  /** No-op singleton used when an agent run has no configured sinks. */
  static final EventEmitter NOOP = new EventEmitter(List.of(), null, "agent");

  private final List<EventSink> sinks;
  private final UUID runId;
  private final String harnessKind;

  EventEmitter(List<EventSink> sinks, UUID runId, String harnessKind) {
    this.sinks = sinks;
    this.runId = runId;
    this.harnessKind = harnessKind;
  }

  boolean active() {
    return !sinks.isEmpty();
  }

  UUID runId() {
    return runId;
  }

  void emitRunStarted(String userInput, SessionContext session) {
    if (!active()) {
      return;
    }
    var attrs = new LinkedHashMap<String, String>();
    if (!Strings.isBlank(userInput)) {
      attrs.put("inputChars", Integer.toString(userInput.length()));
    }
    if (session.userId() != null) {
      attrs.put("userId", session.userId());
    }
    if (session.sessionId() != null) {
      attrs.put("sessionId", session.sessionId().toString());
    }
    dispatch(
        new HeliosEvent.RunStarted(Instant.now(), runId, Optional.empty(), harnessKind, attrs));
  }

  void emitRunCompleted(Trace trace) {
    if (!active() || trace == null) {
      return;
    }
    dispatch(new HeliosEvent.RunCompleted(Instant.now(), runId, Optional.empty(), trace));
  }

  void emitRunFailed(String error, Trace trace) {
    if (!active() || trace == null) {
      return;
    }
    dispatch(
        new HeliosEvent.RunFailed(
            Instant.now(),
            runId,
            Optional.empty(),
            Strings.isBlank(error) ? "unknown" : error,
            trace));
  }

  void emitBeforeApiCall(String userId, UUID sessionId, List<Message> messages, int iteration) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.BeforeApiCall(
            Instant.now(), runId, Optional.empty(), userId, sessionId, messages, iteration));
  }

  void emitAfterTurn(
      String userId,
      UUID sessionId,
      Message userMessage,
      Message assistantMessage,
      List<Message> toolMessages,
      int iteration) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.AfterTurn(
            Instant.now(),
            runId,
            Optional.empty(),
            userId,
            sessionId,
            Optional.ofNullable(userMessage),
            assistantMessage,
            toolMessages,
            iteration));
  }

  void emitBeforeCompaction(String userId, UUID sessionId, List<Message> messages) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.BeforeCompaction(
            Instant.now(), runId, Optional.empty(), userId, sessionId, messages));
  }

  void emitSessionEnd(
      String userId,
      UUID sessionId,
      List<Message> finalMessages,
      HeliosEvent.SessionEnd.Termination termination) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.SessionEnd(
            Instant.now(), runId, Optional.empty(), userId, sessionId, finalMessages, termination));
  }

  void emitIterationStarted(int iteration, int maxIterations) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.IterationStarted(
            Instant.now(), runId, Optional.empty(), iteration, maxIterations));
  }

  void emitIterationCompleted(int iteration) {
    if (!active()) {
      return;
    }
    dispatch(new HeliosEvent.IterationCompleted(Instant.now(), runId, Optional.empty(), iteration));
  }

  void emitAssistantText(String fullText) {
    if (!active() || fullText == null) {
      return;
    }
    dispatch(new HeliosEvent.AssistantText(Instant.now(), runId, Optional.empty(), fullText));
  }

  void emitAssistantThinkingComplete(String fullThinking, String signature) {
    if (!active() || Strings.isBlank(fullThinking)) {
      return;
    }
    dispatch(
        new HeliosEvent.AssistantThinkingComplete(
            Instant.now(), runId, Optional.empty(), fullThinking, Optional.ofNullable(signature)));
  }

  void emitAssistantTextDelta(String text) {
    if (!active() || text == null) {
      return;
    }
    dispatch(new HeliosEvent.AssistantTextDelta(Instant.now(), runId, Optional.empty(), text));
  }

  void emitAssistantThinkingDelta(String text) {
    if (!active() || text == null) {
      return;
    }
    dispatch(new HeliosEvent.AssistantThinkingDelta(Instant.now(), runId, Optional.empty(), text));
  }

  void emitToolCallStarted(String toolCallId, String toolName, Map<String, Object> args) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.ToolCallStarted(
            Instant.now(), runId, Optional.empty(), toolCallId, toolName, args));
  }

  void emitToolCallCompleted(String toolCallId, ToolResult result, Duration took) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.ToolCallCompleted(
            Instant.now(), runId, Optional.empty(), toolCallId, result, took));
  }

  void emitToolCallFailed(String toolCallId, String error) {
    if (!active()) {
      return;
    }
    dispatch(
        new HeliosEvent.ToolCallFailed(
            Instant.now(),
            runId,
            Optional.empty(),
            toolCallId,
            Strings.isBlank(error) ? "unknown" : error));
  }

  private void dispatch(HeliosEvent event) {
    for (var sink : sinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "EventSink threw while handling " + event, e);
      }
    }
  }
}
