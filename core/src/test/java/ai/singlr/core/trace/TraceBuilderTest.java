/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceBuilderTest {

  @Test
  void createAndEndTrace() {
    var trace = TraceBuilder.start("agent-run").end();

    assertNotNull(trace.id());
    assertEquals("agent-run", trace.name());
    assertNotNull(trace.startTime());
    assertNotNull(trace.endTime());
    assertNotNull(trace.duration());
    assertNull(trace.error());
    assertTrue(trace.success());
    assertTrue(trace.spans().isEmpty());
    assertTrue(trace.attributes().isEmpty());
  }

  @Test
  void traceWithMultipleSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL).end();
    builder.span("tool.search", SpanKind.TOOL_EXECUTION).end();

    var trace = builder.end();

    assertEquals(2, trace.spans().size());
    assertEquals("model.chat", trace.spans().get(0).name());
    assertEquals("tool.search", trace.spans().get(1).name());
  }

  @Test
  void traceWithNestedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.span("tool.search", SpanKind.TOOL_EXECUTION);
    var innerModel = toolSpan.span("inner.chat", SpanKind.MODEL_CALL);
    innerModel.end();
    toolSpan.end();

    var trace = builder.end();

    assertEquals(1, trace.spans().size());
    var tool = trace.spans().getFirst();
    assertEquals("tool.search", tool.name());
    assertEquals(1, tool.children().size());
    assertEquals("inner.chat", tool.children().getFirst().name());
  }

  @Test
  void failWithErrorMessage() {
    var trace = TraceBuilder.start("agent-run").fail("model unavailable");

    assertFalse(trace.success());
    assertEquals("model unavailable", trace.error());
  }

  @Test
  void endThrowsIfSpansStillOpen() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL);

    var ex = assertThrows(IllegalStateException.class, builder::end);
    assertTrue(ex.getMessage().contains("1 span(s) still open"));
  }

  @Test
  void failAutoFailsOpenSpans() {
    var builder = TraceBuilder.start("agent-run");
    builder.span("model.chat", SpanKind.MODEL_CALL);
    builder.span("tool.search", SpanKind.TOOL_EXECUTION);

    var trace = builder.fail("agent crashed");

    assertEquals(2, trace.spans().size());
    for (var span : trace.spans()) {
      assertFalse(span.success());
      assertTrue(span.error().contains("Trace 'agent-run' failed"));
    }
  }

  @Test
  void listenersNotifiedOnEnd() {
    var received = new ArrayList<Trace>();
    var builder = TraceBuilder.start("agent-run", List.of(received::add));

    builder.end();

    assertEquals(1, received.size());
    assertEquals("agent-run", received.getFirst().name());
    assertTrue(received.getFirst().success());
  }

  @Test
  void listenersNotifiedOnFail() {
    var received = new ArrayList<Trace>();
    var builder = TraceBuilder.start("agent-run", List.of(received::add));

    builder.fail("boom");

    assertEquals(1, received.size());
    assertFalse(received.getFirst().success());
    assertEquals("boom", received.getFirst().error());
  }

  @Test
  void multipleListenersAllNotified() {
    var received1 = new ArrayList<Trace>();
    var received2 = new ArrayList<Trace>();
    var received3 = new ArrayList<Trace>();
    var builder =
        TraceBuilder.start("agent-run", List.of(received1::add, received2::add, received3::add));

    builder.end();

    assertEquals(1, received1.size());
    assertEquals(1, received2.size());
    assertEquals(1, received3.size());
  }

  @Test
  void listenerExceptionDoesNotPreventOthers() {
    var received = new ArrayList<Trace>();
    TraceListener failing =
        trace -> {
          throw new RuntimeException("listener failed");
        };
    var builder = TraceBuilder.start("agent-run", List.of(failing, received::add));

    builder.end();

    assertEquals(1, received.size());
  }

  @Test
  void failWithMixOfOpenAndClosedSpans() {
    var builder = TraceBuilder.start("agent-run");
    var span1 = builder.span("model.chat", SpanKind.MODEL_CALL);
    builder.span("tool.search", SpanKind.TOOL_EXECUTION);

    span1.end();
    var trace = builder.fail("agent crashed");

    assertEquals(2, trace.spans().size());
    assertTrue(trace.spans().get(0).success());
    assertFalse(trace.spans().get(1).success());
  }

  @Test
  void doubleEndThrows() {
    var builder = TraceBuilder.start("agent-run");

    builder.end();

    var ex = assertThrows(IllegalStateException.class, builder::end);
    assertTrue(ex.getMessage().contains("has already ended"));
  }

  @Test
  void attributesRoundTrip() {
    var builder = TraceBuilder.start("agent-run");
    builder.attribute("agent", "test-agent").attribute("model", "gemini");

    var trace = builder.end();

    assertEquals(Map.of("agent", "test-agent", "model", "gemini"), trace.attributes());
  }

  @Test
  void propagatesContextFields() {
    var sessionId = UUID.randomUUID();
    var builder = TraceBuilder.start("agent-run");
    builder
        .inputText("What is 2+2?")
        .outputText("4")
        .userId("user-1")
        .sessionId(sessionId)
        .modelId("gemini-2.0-flash")
        .promptName("math-agent")
        .promptVersion(2)
        .groupId("eval-batch-1")
        .labels(List.of("math", "test"));

    var trace = builder.end();

    assertEquals("What is 2+2?", trace.inputText());
    assertEquals("4", trace.outputText());
    assertEquals("user-1", trace.userId());
    assertEquals(sessionId, trace.sessionId());
    assertEquals("gemini-2.0-flash", trace.modelId());
    assertEquals("math-agent", trace.promptName());
    assertEquals(2, trace.promptVersion());
    assertEquals("eval-batch-1", trace.groupId());
    assertEquals(List.of("math", "test"), trace.labels());
  }

  @Test
  void computesTotalTokensFromModelCallSpans() {
    var builder = TraceBuilder.start("agent-run");
    var span1 = builder.span("model.chat", SpanKind.MODEL_CALL);
    span1.attribute("inputTokens", "100").attribute("outputTokens", "50");
    span1.end();

    var span2 = builder.span("model.chat", SpanKind.MODEL_CALL);
    span2.attribute("inputTokens", "80").attribute("outputTokens", "30");
    span2.end();

    var trace = builder.end();

    assertEquals(260, trace.totalTokens());
  }

  @Test
  void totalTokensIgnoresNonModelCallSpans() {
    var builder = TraceBuilder.start("agent-run");
    var toolSpan = builder.span("tool.search", SpanKind.TOOL_EXECUTION);
    toolSpan.attribute("inputTokens", "999").attribute("outputTokens", "999");
    toolSpan.end();

    var modelSpan = builder.span("model.chat", SpanKind.MODEL_CALL);
    modelSpan.attribute("inputTokens", "10").attribute("outputTokens", "5");
    modelSpan.end();

    var trace = builder.end();

    assertEquals(15, trace.totalTokens());
  }

  @Test
  void totalTokensDefaultsToZeroWhenNoSpans() {
    var trace = TraceBuilder.start("agent-run").end();

    assertEquals(0, trace.totalTokens());
  }

  @Test
  void thumbsCountsDefaultToZero() {
    var trace = TraceBuilder.start("agent-run").end();

    assertEquals(0, trace.thumbsUpCount());
    assertEquals(0, trace.thumbsDownCount());
  }
}
