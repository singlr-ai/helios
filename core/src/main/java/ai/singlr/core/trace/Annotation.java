/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.Ids;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An annotation on a trace or span, decoupled from the user model.
 *
 * <p>The {@code label} field provides a category (e.g., "quality", "relevance", "accuracy"),
 * enabling multiple annotations per target.
 *
 * @param id unique identifier
 * @param targetId the UUID of the trace or span this annotation is attached to
 * @param label category label (e.g., "quality", "relevance", "accuracy")
 * @param rating optional numeric rating (e.g., -1, 0, or 1)
 * @param comment optional free text
 * @param createdAt when this annotation was created
 */
public record Annotation(
    UUID id,
    UUID targetId,
    String label,
    Integer rating,
    String comment,
    OffsetDateTime createdAt) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Annotation annotation) {
    return new Builder(annotation);
  }

  /** Builder for Annotation. */
  public static class Builder {

    private UUID id;
    private UUID targetId;
    private String label;
    private Integer rating;
    private String comment;
    private OffsetDateTime createdAt;

    private Builder() {}

    private Builder(Annotation annotation) {
      this.id = annotation.id;
      this.targetId = annotation.targetId;
      this.label = annotation.label;
      this.rating = annotation.rating;
      this.comment = annotation.comment;
      this.createdAt = annotation.createdAt;
    }

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withTargetId(UUID targetId) {
      this.targetId = targetId;
      return this;
    }

    public Builder withLabel(String label) {
      this.label = label;
      return this;
    }

    public Builder withRating(Integer rating) {
      this.rating = rating;
      return this;
    }

    public Builder withComment(String comment) {
      this.comment = comment;
      return this;
    }

    public Builder withCreatedAt(OffsetDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    /**
     * Builds the Annotation. Auto-generates id and createdAt if not set.
     *
     * @throws IllegalStateException if targetId or label is not set
     */
    public Annotation build() {
      if (targetId == null) {
        throw new IllegalStateException("targetId is required");
      }
      if (label == null || label.isBlank()) {
        throw new IllegalStateException("label is required");
      }
      if (id == null) {
        id = Ids.newId();
      }
      if (createdAt == null) {
        createdAt = Ids.now();
      }
      return new Annotation(id, targetId, label, rating, comment, createdAt);
    }
  }
}
