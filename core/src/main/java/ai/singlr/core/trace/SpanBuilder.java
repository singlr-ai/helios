/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.Ids;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mutable builder for constructing spans during agent execution.
 *
 * <p>Created by {@link TraceBuilder#span(String, SpanKind)} or {@link #span(String, SpanKind)} for
 * nesting. Call {@link #end()} to complete successfully or {@link #fail(String)} to complete with
 * an error. Both produce an immutable {@link Span}.
 *
 * <p>Span lifecycle ({@code SpanOpened} / {@code SpanClosed}) is emitted directly to every
 * configured {@link EventSink} — no separate listener interface in between. The unified event
 * stream carries everything observers need; {@link UUID#runId} on each event allows multiplexing
 * concurrent runs through the same sink.
 *
 * <p>Not thread-safe. Designed for sequential use within an agent loop.
 */
public final class SpanBuilder implements SpanContainer {

  private static final Logger LOG = Logger.getLogger(SpanBuilder.class.getName());

  private final UUID id;
  private final UUID traceId;
  private final UUID parentSpanId;
  private final String name;
  private final SpanKind kind;
  private final OffsetDateTime startTime;
  private final List<EventSink> eventSinks;
  private final UUID runId;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<SpanBuilder> openChildren = new ArrayList<>();
  private final List<Span> completedChildren = new ArrayList<>();
  private Span result;

  SpanBuilder(
      String name,
      SpanKind kind,
      UUID traceId,
      UUID parentSpanId,
      List<EventSink> eventSinks,
      UUID runId) {
    this.id = Ids.newId();
    this.traceId = traceId;
    this.parentSpanId = parentSpanId;
    this.name = name;
    this.kind = kind;
    this.startTime = Ids.now();
    this.eventSinks = eventSinks;
    this.runId = runId;
    fireSpanOpened();
  }

  /**
   * Creates a nested child span.
   *
   * @param name the span name
   * @param kind the span kind
   * @return the child SpanBuilder
   * @throws IllegalStateException if this span has already ended
   */
  @Override
  public SpanBuilder span(String name, SpanKind kind) {
    requireOpen();
    var child = new SpanBuilder(name, kind, traceId, this.id, eventSinks, runId);
    openChildren.add(child);
    return child;
  }

  /**
   * Adds a key-value attribute to this span.
   *
   * @param key the attribute key
   * @param value the attribute value
   * @return this builder for chaining
   * @throws IllegalStateException if this span has already ended
   */
  public SpanBuilder attribute(String key, String value) {
    requireOpen();
    attributes.put(key, value);
    return this;
  }

  /**
   * Completes this span successfully.
   *
   * @return the immutable Span
   * @throws IllegalStateException if this span has already ended
   * @throws IllegalStateException if any child spans are still open
   */
  public Span end() {
    requireOpen();
    collectCompletedChildren();
    if (!openChildren.isEmpty()) {
      throw new IllegalStateException(
          "Cannot end span '%s': %d child span(s) still open".formatted(name, openChildren.size()));
    }
    return complete(null);
  }

  /**
   * Completes this span with an error. Auto-fails any open children.
   *
   * @param error the error message
   * @return the immutable Span
   * @throws IllegalStateException if this span has already ended
   */
  public Span fail(String error) {
    requireOpen();
    collectCompletedChildren();
    failOpenChildren(error);
    return complete(error);
  }

  boolean isOpen() {
    return result == null;
  }

  Span result() {
    return result;
  }

  /**
   * Total child spans added so far (both still-open and completed). Used by {@code Agent} to record
   * how many spans a nested sub-run contributed to this parent, surfacing that in the {@code
   * subAgent.spanCount} attribute for diagnosability.
   *
   * @return total child span count
   */
  public int openChildCount() {
    return openChildren.size() + completedChildren.size();
  }

  private Span complete(String error) {
    var endTime = Ids.now();
    var duration = Duration.between(startTime, endTime);
    result =
        new Span(
            id,
            name,
            kind,
            startTime,
            endTime,
            duration,
            error,
            List.copyOf(completedChildren),
            Map.copyOf(attributes));
    fireSpanClosed(result);
    return result;
  }

  private void collectCompletedChildren() {
    var stillOpen = new ArrayList<SpanBuilder>();
    for (var child : openChildren) {
      if (!child.isOpen()) {
        completedChildren.add(child.result());
      } else {
        stillOpen.add(child);
      }
    }
    openChildren.clear();
    openChildren.addAll(stillOpen);
  }

  private void failOpenChildren(String parentError) {
    for (var child : openChildren) {
      if (child.isOpen()) {
        completedChildren.add(
            child.fail("Parent span '%s' failed: %s".formatted(name, parentError)));
      }
    }
    openChildren.clear();
  }

  private void fireSpanOpened() {
    if (eventSinks == null || eventSinks.isEmpty() || runId == null) {
      return;
    }
    var event =
        new HeliosEvent.SpanOpened(
            Instant.now(), runId, Optional.empty(), id, Optional.ofNullable(parentSpanId), name);
    fanOut(event);
  }

  private void fireSpanClosed(Span span) {
    if (eventSinks == null || eventSinks.isEmpty() || runId == null) {
      return;
    }
    var event =
        new HeliosEvent.SpanClosed(
            Instant.now(),
            runId,
            Optional.empty(),
            span.id(),
            span.duration() == null ? Duration.ZERO : span.duration(),
            span.success(),
            Optional.ofNullable(span.error()));
    fanOut(event);
  }

  private void fanOut(HeliosEvent event) {
    for (var sink : eventSinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "EventSink threw on " + event.getClass().getSimpleName(), e);
      }
    }
  }

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("Span '%s' has already ended".formatted(name));
    }
  }
}
