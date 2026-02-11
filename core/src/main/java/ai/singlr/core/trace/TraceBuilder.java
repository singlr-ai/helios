/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.Ids;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable builder for constructing traces during agent execution.
 *
 * <p>Create with {@link #start(String)} or {@link #start(String, List)}. Add spans with {@link
 * #span(String, SpanKind)}, then call {@link #end()} or {@link #fail(String)} to produce an
 * immutable {@link Trace} and notify listeners.
 *
 * <p>Not thread-safe. Designed for sequential use within an agent loop.
 */
public class TraceBuilder {

  private final UUID id;
  private final String name;
  private final OffsetDateTime startTime;
  private final List<TraceListener> listeners;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<SpanBuilder> openSpans = new ArrayList<>();
  private final List<Span> completedSpans = new ArrayList<>();
  private boolean ended;

  private String inputText;
  private String outputText;
  private String userId;
  private UUID sessionId;
  private String modelId;
  private String promptName;
  private Integer promptVersion;
  private String groupId;
  private List<String> labels = List.of();

  private TraceBuilder(String name, List<TraceListener> listeners) {
    this.id = Ids.newId();
    this.name = name;
    this.startTime = Ids.now();
    this.listeners = List.copyOf(listeners);
  }

  /**
   * Starts a new trace with no listeners.
   *
   * @param name the trace name
   * @return a new TraceBuilder
   */
  public static TraceBuilder start(String name) {
    return new TraceBuilder(name, List.of());
  }

  /**
   * Starts a new trace with listeners.
   *
   * @param name the trace name
   * @param listeners the listeners to notify on completion
   * @return a new TraceBuilder
   */
  public static TraceBuilder start(String name, List<TraceListener> listeners) {
    return new TraceBuilder(name, listeners);
  }

  /**
   * Creates a new top-level span within this trace.
   *
   * @param name the span name
   * @param kind the span kind
   * @return the SpanBuilder
   * @throws IllegalStateException if this trace has already ended
   */
  public SpanBuilder span(String name, SpanKind kind) {
    requireOpen();
    var span = new SpanBuilder(name, kind);
    openSpans.add(span);
    return span;
  }

  /**
   * Adds a key-value attribute to this trace.
   *
   * @param key the attribute key
   * @param value the attribute value
   * @return this builder for chaining
   * @throws IllegalStateException if this trace has already ended
   */
  public TraceBuilder attribute(String key, String value) {
    requireOpen();
    attributes.put(key, value);
    return this;
  }

  public TraceBuilder inputText(String inputText) {
    this.inputText = inputText;
    return this;
  }

  public TraceBuilder outputText(String outputText) {
    this.outputText = outputText;
    return this;
  }

  public TraceBuilder userId(String userId) {
    this.userId = userId;
    return this;
  }

  public TraceBuilder sessionId(UUID sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  public TraceBuilder modelId(String modelId) {
    this.modelId = modelId;
    return this;
  }

  public TraceBuilder promptName(String promptName) {
    this.promptName = promptName;
    return this;
  }

  public TraceBuilder promptVersion(Integer promptVersion) {
    this.promptVersion = promptVersion;
    return this;
  }

  public TraceBuilder groupId(String groupId) {
    this.groupId = groupId;
    return this;
  }

  public TraceBuilder labels(List<String> labels) {
    this.labels = labels != null ? List.copyOf(labels) : List.of();
    return this;
  }

  /**
   * Completes this trace successfully. Notifies all listeners.
   *
   * @return the immutable Trace
   * @throws IllegalStateException if this trace has already ended
   * @throws IllegalStateException if any spans are still open
   */
  public Trace end() {
    requireOpen();
    collectCompletedSpans();
    if (!openSpans.isEmpty()) {
      throw new IllegalStateException(
          "Cannot end trace '%s': %d span(s) still open".formatted(name, openSpans.size()));
    }
    var trace = complete(null);
    notifyListeners(trace);
    return trace;
  }

  /**
   * Completes this trace with an error. Auto-fails any open spans. Notifies all listeners.
   *
   * @param error the error message
   * @return the immutable Trace
   * @throws IllegalStateException if this trace has already ended
   */
  public Trace fail(String error) {
    requireOpen();
    collectCompletedSpans();
    failOpenSpans(error);
    var trace = complete(error);
    notifyListeners(trace);
    return trace;
  }

  private Trace complete(String error) {
    ended = true;
    var endTime = Ids.now();
    var duration = Duration.between(startTime, endTime);
    var totalTokens = computeTotalTokens();
    return new Trace(
        id,
        name,
        startTime,
        endTime,
        duration,
        error,
        List.copyOf(completedSpans),
        Map.copyOf(attributes),
        inputText,
        outputText,
        userId,
        sessionId,
        modelId,
        promptName,
        promptVersion,
        totalTokens,
        0,
        0,
        groupId,
        labels);
  }

  private int computeTotalTokens() {
    int total = 0;
    for (var span : completedSpans) {
      if (span.kind() == SpanKind.MODEL_CALL) {
        var input = span.attributes().get("inputTokens");
        var output = span.attributes().get("outputTokens");
        if (input != null) {
          total += Integer.parseInt(input);
        }
        if (output != null) {
          total += Integer.parseInt(output);
        }
      }
    }
    return total;
  }

  private void collectCompletedSpans() {
    var stillOpen = new ArrayList<SpanBuilder>();
    for (var sb : openSpans) {
      if (!sb.isOpen()) {
        completedSpans.add(sb.result());
      } else {
        stillOpen.add(sb);
      }
    }
    openSpans.clear();
    openSpans.addAll(stillOpen);
  }

  private void failOpenSpans(String traceError) {
    for (var sb : openSpans) {
      if (sb.isOpen()) {
        completedSpans.add(sb.fail("Trace '%s' failed: %s".formatted(name, traceError)));
      }
    }
    openSpans.clear();
  }

  private void notifyListeners(Trace trace) {
    for (var listener : listeners) {
      try {
        listener.onTrace(trace);
      } catch (Exception ignored) {
        // Listener exceptions must not prevent other listeners from being notified
      }
    }
  }

  private void requireOpen() {
    if (ended) {
      throw new IllegalStateException("Trace '%s' has already ended".formatted(name));
    }
  }
}
