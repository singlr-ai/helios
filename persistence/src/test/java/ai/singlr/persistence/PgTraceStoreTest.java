/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.trace.Annotation;
import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgTraceStoreTest {

  private PgTraceStore store;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
    store = new PgTraceStore(PgTestSupport.pgConfig());
  }

  @Test
  void storeAndFindMinimalTrace() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(5);
    var trace =
        Trace.newBuilder().withName("agent-run").withStartTime(start).withEndTime(end).build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNotNull(found);
    assertEquals(trace.id(), found.id());
    assertEquals("agent-run", found.name());
    assertEquals(start, found.startTime());
    assertEquals(end, found.endTime());
    assertNull(found.error());
    assertTrue(found.success());
    assertTrue(found.spans().isEmpty());
    assertTrue(found.attributes().isEmpty());
  }

  @Test
  void storeTraceWithAttributes() {
    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1))
            .withAttribute("agent", "test-agent")
            .withAttribute("model", "gemini")
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(Map.of("agent", "test-agent", "model", "gemini"), found.attributes());
  }

  @Test
  void storeTraceWithError() {
    var trace =
        Trace.newBuilder()
            .withName("failed-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withError("model unavailable")
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertFalse(found.success());
    assertEquals("model unavailable", found.error());
  }

  @Test
  void storeTraceWithSingleSpan() {
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(OffsetDateTime.now(ZoneOffset.UTC))
            .withEndTime(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(3))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    assertEquals("model.chat", found.spans().getFirst().name());
    assertEquals(SpanKind.MODEL_CALL, found.spans().getFirst().kind());
    assertTrue(found.spans().getFirst().success());
  }

  @Test
  void storeTraceWithMultipleTopLevelSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span1 =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var span2 =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now.plusSeconds(1))
            .withEndTime(now.plusSeconds(2))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(span1)
            .withSpan(span2)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(2, found.spans().size());
    assertEquals("model.chat", found.spans().get(0).name());
    assertEquals("tool.search", found.spans().get(1).name());
  }

  @Test
  void storeTraceWithNestedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var childSpan =
        Span.newBuilder()
            .withName("inner.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var parentSpan =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withChild(childSpan)
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(parentSpan)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    var tool = found.spans().getFirst();
    assertEquals("tool.search", tool.name());
    assertEquals(1, tool.children().size());
    assertEquals("inner.chat", tool.children().getFirst().name());
    assertEquals(SpanKind.MODEL_CALL, tool.children().getFirst().kind());
  }

  @Test
  void spanKindRoundTrip() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var trace =
        Trace.newBuilder()
            .withName("kinds")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(5))
            .build();
    var traceBuilder = Trace.newBuilder(trace);

    for (var kind : SpanKind.values()) {
      traceBuilder.withSpan(
          Span.newBuilder()
              .withName("span-" + kind.name())
              .withKind(kind)
              .withStartTime(now)
              .withEndTime(now.plusSeconds(1))
              .build());
    }

    var traceWithSpans = traceBuilder.build();
    store.store(traceWithSpans);
    var found = store.findById(traceWithSpans.id());

    assertEquals(SpanKind.values().length, found.spans().size());
    for (int i = 0; i < SpanKind.values().length; i++) {
      assertEquals(SpanKind.values()[i], found.spans().get(i).kind());
    }
  }

  @Test
  void spanAttributesRoundTrip() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .withAttribute("model", "gemini")
            .withAttribute("tokens", "150")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(Map.of("model", "gemini", "tokens", "150"), found.spans().getFirst().attributes());
  }

  @Test
  void spanWithError() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .withError("connection timeout")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("agent-run")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertFalse(found.spans().getFirst().success());
    assertEquals("connection timeout", found.spans().getFirst().error());
  }

  @Test
  void findNonExistentTraceReturnsNull() {
    assertNull(store.findById(UUID.randomUUID()));
  }

  @Test
  void storeAndFindAnnotationWithAllFields() {
    var targetId = UUID.randomUUID();
    var annotation =
        Annotation.newBuilder()
            .withTargetId(targetId)
            .withLabel("quality")
            .withRating(1)
            .withComment("Great response")
            .build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotations(targetId);

    assertEquals(1, found.size());
    var a = found.getFirst();
    assertEquals(annotation.id(), a.id());
    assertEquals(targetId, a.targetId());
    assertEquals("quality", a.label());
    assertEquals(1, a.rating());
    assertEquals("Great response", a.comment());
    assertNotNull(a.createdAt());
  }

  @Test
  void storeAnnotationWithNullableFields() {
    var targetId = UUID.randomUUID();
    var annotation = Annotation.newBuilder().withTargetId(targetId).withLabel("flag").build();

    store.storeAnnotation(annotation);
    var found = store.findAnnotations(targetId);

    assertEquals(1, found.size());
    assertNull(found.getFirst().rating());
    assertNull(found.getFirst().comment());
  }

  @Test
  void multipleAnnotationsForSameTarget() {
    var targetId = UUID.randomUUID();
    store.storeAnnotation(
        Annotation.newBuilder().withTargetId(targetId).withLabel("quality").withRating(1).build());
    store.storeAnnotation(
        Annotation.newBuilder()
            .withTargetId(targetId)
            .withLabel("relevance")
            .withRating(-1)
            .build());

    var found = store.findAnnotations(targetId);
    assertEquals(2, found.size());
  }

  @Test
  void findAnnotationsForNonExistentTargetReturnsEmpty() {
    assertTrue(store.findAnnotations(UUID.randomUUID()).isEmpty());
  }

  @Test
  void onTraceStoresTraceAndSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var span =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();

    var trace =
        Trace.newBuilder()
            .withName("listener-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withSpan(span)
            .build();

    store.onTrace(trace);
    var found = store.findById(trace.id());

    assertNotNull(found);
    assertEquals("listener-trace", found.name());
    assertEquals(1, found.spans().size());
  }

  @Test
  void deeplyNestedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var grandchild =
        Span.newBuilder()
            .withName("grandchild")
            .withKind(SpanKind.CUSTOM)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var child =
        Span.newBuilder()
            .withName("child")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(2))
            .withChild(grandchild)
            .build();
    var parent =
        Span.newBuilder()
            .withName("parent")
            .withKind(SpanKind.AGENT)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withChild(child)
            .build();

    var trace =
        Trace.newBuilder()
            .withName("deep-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(4))
            .withSpan(parent)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(1, found.spans().size());
    var p = found.spans().getFirst();
    assertEquals("parent", p.name());
    assertEquals(1, p.children().size());
    var c = p.children().getFirst();
    assertEquals("child", c.name());
    assertEquals(1, c.children().size());
    assertEquals("grandchild", c.children().getFirst().name());
  }

  @Test
  void mixOfSuccessfulAndFailedSpans() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var successSpan =
        Span.newBuilder()
            .withName("model.chat")
            .withKind(SpanKind.MODEL_CALL)
            .withStartTime(now)
            .withEndTime(now.plusSeconds(1))
            .build();
    var failedSpan =
        Span.newBuilder()
            .withName("tool.search")
            .withKind(SpanKind.TOOL_EXECUTION)
            .withStartTime(now.plusSeconds(1))
            .withEndTime(now.plusSeconds(2))
            .withError("tool crashed")
            .build();

    var trace =
        Trace.newBuilder()
            .withName("mixed-trace")
            .withStartTime(now)
            .withEndTime(now.plusSeconds(3))
            .withSpan(successSpan)
            .withSpan(failedSpan)
            .build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertEquals(2, found.spans().size());
    assertTrue(found.spans().get(0).success());
    assertFalse(found.spans().get(1).success());
    assertEquals("tool crashed", found.spans().get(1).error());
  }

  @Test
  void durationComputedFromTimes() {
    var start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var end = start.plusSeconds(7);
    var trace = Trace.newBuilder().withName("timed").withStartTime(start).withEndTime(end).build();

    store.store(trace);
    var found = store.findById(trace.id());

    assertNotNull(found.duration());
    assertEquals(Duration.ofSeconds(7), found.duration());
  }
}
