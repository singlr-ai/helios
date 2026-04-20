/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import ai.singlr.core.common.Ids;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row in an {@link ExperimentLog}.
 *
 * <p>Each entry records one iteration of the autoresearch loop — the candidate's score, whether the
 * loop kept or discarded it, a free-form description from the agent, and ASI (Actionable Side
 * Information) the agent writes for its future self after a context reset.
 *
 * <p>ASI values are strings by contract. Callers that need structured data should serialize to JSON
 * themselves — the constraint keeps the on-disk format simple and unambiguous.
 *
 * @param id unique entry id (UUID v7)
 * @param segment segment this entry belongs to; segments let users re-baseline without losing
 *     history
 * @param status one of {@code "keep"}, {@code "discard"}, {@code "crash"}
 * @param primaryMetric the objective's primary score; must be a finite number
 * @param secondaryMetrics tradeoff metrics; all values must be finite
 * @param description the agent's short description of what was tried
 * @param asi free-form diagnostics the agent writes for future iterations
 * @param confidence session confidence score at the time this entry was logged; {@code null} if
 *     there were fewer than three entries
 * @param timestamp when the entry was created
 */
public record ExperimentEntry(
    UUID id,
    int segment,
    String status,
    double primaryMetric,
    Map<String, Double> secondaryMetrics,
    String description,
    Map<String, String> asi,
    Double confidence,
    Instant timestamp) {

  /** Status values allowed on an entry. */
  public static final String STATUS_KEEP = "keep";

  public static final String STATUS_DISCARD = "discard";
  public static final String STATUS_CRASH = "crash";

  public ExperimentEntry {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (segment < 0) {
      throw new IllegalArgumentException("segment must not be negative");
    }
    if (status == null
        || !(status.equals(STATUS_KEEP)
            || status.equals(STATUS_DISCARD)
            || status.equals(STATUS_CRASH))) {
      throw new IllegalArgumentException(
          "status must be one of keep|discard|crash, got: " + status);
    }
    if (!Double.isFinite(primaryMetric)) {
      throw new IllegalArgumentException("primaryMetric must be finite, got: " + primaryMetric);
    }
    if (secondaryMetrics == null) {
      secondaryMetrics = Map.of();
    } else {
      for (var entry : secondaryMetrics.entrySet()) {
        var v = entry.getValue();
        if (v == null || !Double.isFinite(v)) {
          throw new IllegalArgumentException(
              "secondaryMetrics value for " + entry.getKey() + " must be finite, got: " + v);
        }
      }
      secondaryMetrics = Map.copyOf(secondaryMetrics);
    }
    if (description == null) {
      throw new IllegalArgumentException("description must not be null");
    }
    asi = asi == null ? Map.of() : Map.copyOf(asi);
    if (confidence != null && !Double.isFinite(confidence)) {
      throw new IllegalArgumentException("confidence must be finite or null, got: " + confidence);
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp must not be null");
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for {@link ExperimentEntry}. */
  public static final class Builder {

    private UUID id;
    private int segment;
    private String status;
    private double primaryMetric;
    private Map<String, Double> secondaryMetrics = Map.of();
    private String description = "";
    private Map<String, String> asi = Map.of();
    private Double confidence;
    private Instant timestamp;

    private Builder() {}

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withSegment(int segment) {
      this.segment = segment;
      return this;
    }

    public Builder withStatus(String status) {
      this.status = status;
      return this;
    }

    public Builder withPrimaryMetric(double primaryMetric) {
      this.primaryMetric = primaryMetric;
      return this;
    }

    public Builder withSecondaryMetrics(Map<String, Double> secondaryMetrics) {
      this.secondaryMetrics = secondaryMetrics;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withAsi(Map<String, String> asi) {
      this.asi = asi;
      return this;
    }

    public Builder withConfidence(Double confidence) {
      this.confidence = confidence;
      return this;
    }

    public Builder withTimestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public ExperimentEntry build() {
      return new ExperimentEntry(
          id == null ? Ids.newId() : id,
          segment,
          status,
          primaryMetric,
          secondaryMetrics,
          description,
          asi,
          confidence,
          timestamp == null ? Instant.now() : timestamp);
    }
  }
}
