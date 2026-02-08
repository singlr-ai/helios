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
 * An immutable trace representing a complete agent execution.
 *
 * @param id unique identifier
 * @param name descriptive name for this trace
 * @param startTime when the trace started
 * @param endTime when the trace ended
 * @param duration wall-clock duration
 * @param error error message, or null if the trace succeeded
 * @param spans top-level spans within this trace
 * @param attributes key-value metadata
 */
public record Trace(
    UUID id,
    String name,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Duration duration,
    String error,
    List<Span> spans,
    Map<String, String> attributes) {

  /** Returns true if this trace completed without error. */
  public boolean success() {
    return error == null;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Trace trace) {
    return new Builder(trace);
  }

  /** Builder for Trace. Used by persistence module to reconstruct from DB rows. */
  public static class Builder {

    private UUID id;
    private String name;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Duration duration;
    private String error;
    private List<Span> spans = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();

    private Builder() {}

    private Builder(Trace trace) {
      this.id = trace.id;
      this.name = trace.name;
      this.startTime = trace.startTime;
      this.endTime = trace.endTime;
      this.duration = trace.duration;
      this.error = trace.error;
      this.spans = new ArrayList<>(trace.spans);
      this.attributes = new LinkedHashMap<>(trace.attributes);
    }

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withStartTime(OffsetDateTime startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder withEndTime(OffsetDateTime endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder withDuration(Duration duration) {
      this.duration = duration;
      return this;
    }

    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    public Builder withSpans(List<Span> spans) {
      this.spans = new ArrayList<>(spans);
      return this;
    }

    public Builder withSpan(Span span) {
      this.spans.add(span);
      return this;
    }

    public Builder withAttributes(Map<String, String> attributes) {
      this.attributes = new LinkedHashMap<>(attributes);
      return this;
    }

    public Builder withAttribute(String key, String value) {
      this.attributes.put(key, value);
      return this;
    }

    /**
     * Builds the Trace. Auto-generates id and startTime if not set. Computes duration from
     * startTime and endTime if duration not explicitly set.
     */
    public Trace build() {
      if (id == null) {
        id = Ids.newId();
      }
      if (startTime == null) {
        startTime = Ids.now();
      }
      if (duration == null && endTime != null) {
        duration = Duration.between(startTime, endTime);
      }
      return new Trace(
          id,
          name,
          startTime,
          endTime,
          duration,
          error,
          List.copyOf(spans),
          Map.copyOf(attributes));
    }
  }
}
