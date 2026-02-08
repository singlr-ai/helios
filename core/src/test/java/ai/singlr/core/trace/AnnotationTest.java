/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnotationTest {

  @Test
  void builderDefaults() {
    var targetId = UUID.randomUUID();

    var annotation =
        Annotation.newBuilder()
            .withTargetId(targetId)
            .withLabel("quality")
            .withRating(1)
            .withComment("Great response")
            .build();

    assertNotNull(annotation.id());
    assertEquals(targetId, annotation.targetId());
    assertEquals("quality", annotation.label());
    assertEquals(1, annotation.rating());
    assertEquals("Great response", annotation.comment());
    assertNotNull(annotation.createdAt());
  }

  @Test
  void builderRoundTrip() {
    var original =
        Annotation.newBuilder()
            .withTargetId(UUID.randomUUID())
            .withLabel("relevance")
            .withRating(-1)
            .withComment("Off topic")
            .build();

    var copy = Annotation.newBuilder(original).build();

    assertEquals(original.id(), copy.id());
    assertEquals(original.targetId(), copy.targetId());
    assertEquals(original.label(), copy.label());
    assertEquals(original.rating(), copy.rating());
    assertEquals(original.comment(), copy.comment());
    assertEquals(original.createdAt(), copy.createdAt());
  }

  @Test
  void builderWithExplicitIdAndCreatedAt() {
    var id = UUID.randomUUID();
    var targetId = UUID.randomUUID();
    var createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    var annotation =
        Annotation.newBuilder()
            .withId(id)
            .withTargetId(targetId)
            .withLabel("accuracy")
            .withRating(0)
            .withComment("Neutral")
            .withCreatedAt(createdAt)
            .build();

    assertEquals(id, annotation.id());
    assertEquals(targetId, annotation.targetId());
    assertEquals(createdAt, annotation.createdAt());
  }

  @Test
  void optionalFieldsCanBeNull() {
    var annotation =
        Annotation.newBuilder().withTargetId(UUID.randomUUID()).withLabel("flag").build();

    assertNull(annotation.rating());
    assertNull(annotation.comment());
  }
}
