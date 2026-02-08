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
 * Mutable builder for constructing spans during agent execution.
 *
 * <p>Created by {@link TraceBuilder#span(String, SpanKind)} or {@link #span(String, SpanKind)} for
 * nesting. Call {@link #end()} to complete successfully or {@link #fail(String)} to complete with
 * an error. Both produce an immutable {@link Span}.
 *
 * <p>Not thread-safe. Designed for sequential use within an agent loop.
 */
public class SpanBuilder {

  private final UUID id;
  private final String name;
  private final SpanKind kind;
  private final OffsetDateTime startTime;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<SpanBuilder> openChildren = new ArrayList<>();
  private final List<Span> completedChildren = new ArrayList<>();
  private Span result;

  SpanBuilder(String name, SpanKind kind) {
    this.id = Ids.newId();
    this.name = name;
    this.kind = kind;
    this.startTime = Ids.now();
  }

  /**
   * Creates a nested child span.
   *
   * @param name the span name
   * @param kind the span kind
   * @return the child SpanBuilder
   * @throws IllegalStateException if this span has already ended
   */
  public SpanBuilder span(String name, SpanKind kind) {
    requireOpen();
    var child = new SpanBuilder(name, kind);
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

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("Span '%s' has already ended".formatted(name));
    }
  }
}
