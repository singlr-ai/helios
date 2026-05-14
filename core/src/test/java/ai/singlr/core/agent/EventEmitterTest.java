/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.events.CollectingEventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Message;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEmitterTest {

  @Test
  void noopEmitterIsInactive() {
    assertFalse(EventEmitter.NOOP.active());
  }

  @Test
  void activeReportsBasedOnSinkPresence() {
    var inactive = new EventEmitter(List.of(), Ids.newId(), "agent");
    assertFalse(inactive.active());

    var sink = new CollectingEventSink();
    var active = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    assertTrue(active.active());
  }

  @Test
  void runIdAccessor() {
    var id = Ids.newId();
    var emitter = new EventEmitter(List.of(), id, "agent");
    assertEquals(id, emitter.runId());
  }

  @Test
  void inactiveEmitterSkipsAllDispatch() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(), Ids.newId(), "agent");

    emitter.emitRunStarted("hi", SessionContext.of("hi"));
    emitter.emitRunCompleted(Trace.newBuilder().build());
    emitter.emitRunFailed("err", Trace.newBuilder().build());
    emitter.emitIterationStarted(0, 10);
    emitter.emitIterationCompleted(0);
    emitter.emitAssistantText("x");
    emitter.emitAssistantTextDelta("x");
    emitter.emitAssistantThinkingDelta("x");
    emitter.emitAssistantThinkingComplete("x", null);
    emitter.emitToolCallStarted("c", "n", Map.of());
    emitter.emitToolCallCompleted("c", ToolResult.success("ok"), Duration.ZERO);
    emitter.emitToolCallFailed("c", "err");
    emitter.emitBeforeApiCall("u", Ids.newId(), List.of(), 0);
    emitter.emitAfterTurn("u", Ids.newId(), null, Message.assistant("ok"), List.of(), 0);
    emitter.emitBeforeCompaction("u", Ids.newId(), List.of());
    emitter.emitSessionEnd(
        "u", Ids.newId(), List.of(), HeliosEvent.SessionEnd.Termination.COMPLETED);

    assertEquals(0, sink.size(), "inactive emitter should not dispatch anything");
  }

  @Test
  void runStartedCarriesHarnessKindAndSessionAttributes() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "rlm-harness");
    var session =
        SessionContext.newBuilder().withUserInput("Hello world").withUserId("u-42").build();

    emitter.emitRunStarted("Hello world", session);

    var started = (HeliosEvent.RunStarted) sink.events().get(0);
    assertEquals("rlm-harness", started.harnessKind());
    assertEquals("u-42", started.attributes().get("userId"));
    assertEquals("11", started.attributes().get("inputChars"));
  }

  @Test
  void runStartedOmitsAttributesForBlankInputAndNullSessionFields() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");

    emitter.emitRunStarted("", SessionContext.of("ignored"));
    var started = (HeliosEvent.RunStarted) sink.events().get(0);
    assertFalse(started.attributes().containsKey("inputChars"));
  }

  @Test
  void runCompletedSkippedWhenTraceIsNull() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitRunCompleted(null);
    assertEquals(0, sink.size());
  }

  @Test
  void runFailedSkippedWhenTraceIsNull() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitRunFailed("err", null);
    assertEquals(0, sink.size());
  }

  @Test
  void runFailedFallsBackToUnknownForBlankError() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitRunFailed("  ", Trace.newBuilder().build());

    var failed = (HeliosEvent.RunFailed) sink.events().get(0);
    assertEquals("unknown", failed.error());
  }

  @Test
  void assistantTextSkippedForNullText() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitAssistantText(null);
    emitter.emitAssistantTextDelta(null);
    emitter.emitAssistantThinkingDelta(null);
    assertEquals(0, sink.size());
  }

  @Test
  void thinkingCompleteSkippedWhenBlank() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitAssistantThinkingComplete("", null);
    emitter.emitAssistantThinkingComplete("   ", null);
    emitter.emitAssistantThinkingComplete(null, null);
    assertEquals(0, sink.size());
  }

  @Test
  void thinkingCompleteCarriesSignatureWhenSupplied() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitAssistantThinkingComplete("reasoning", "sig-1");

    var ev = (HeliosEvent.AssistantThinkingComplete) sink.events().get(0);
    assertTrue(ev.signature().isPresent());
    assertEquals("sig-1", ev.signature().get());
  }

  @Test
  void toolCallFailedFallsBackToUnknownForBlankError() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitToolCallFailed("c1", null);

    var ev = (HeliosEvent.ToolCallFailed) sink.events().get(0);
    assertEquals("unknown", ev.error());
  }

  @Test
  void beforeApiCallCarriesIterationAndMessageList() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    var msgs = List.of(Message.user("hi"));
    emitter.emitBeforeApiCall("u", Ids.newId(), msgs, 3);

    var ev = (HeliosEvent.BeforeApiCall) sink.events().get(0);
    assertEquals(3, ev.iteration());
    assertEquals(1, ev.messages().size());
  }

  @Test
  void afterTurnCarriesUserAndAssistantMessages() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    var user = Message.user("q");
    var assistant = Message.assistant("a");
    emitter.emitAfterTurn("u", Ids.newId(), user, assistant, List.of(), 2);

    var ev = (HeliosEvent.AfterTurn) sink.events().get(0);
    assertTrue(ev.userMessage().isPresent());
    assertEquals(user, ev.userMessage().get());
    assertEquals(assistant, ev.assistantMessage());
    assertEquals(2, ev.iteration());
  }

  @Test
  void afterTurnAllowsNullUserMessage() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitAfterTurn("u", Ids.newId(), null, Message.assistant("a"), List.of(), 0);

    var ev = (HeliosEvent.AfterTurn) sink.events().get(0);
    assertFalse(ev.userMessage().isPresent());
  }

  @Test
  void beforeCompactionCarriesMessageList() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitBeforeCompaction("u", Ids.newId(), List.of(Message.user("x")));

    var ev = (HeliosEvent.BeforeCompaction) sink.events().get(0);
    assertEquals(1, ev.messages().size());
  }

  @Test
  void sessionEndCarriesTerminationAndFinalMessages() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    emitter.emitSessionEnd(
        "u", Ids.newId(), List.of(Message.user("x")), HeliosEvent.SessionEnd.Termination.FAILED);

    var ev = (HeliosEvent.SessionEnd) sink.events().get(0);
    assertEquals(HeliosEvent.SessionEnd.Termination.FAILED, ev.termination());
    assertEquals(1, ev.finalMessages().size());
  }

  @Test
  void dispatchSwallowsSinkException() {
    var good = new CollectingEventSink();
    var emitter =
        new EventEmitter(
            List.of(
                e -> {
                  throw new RuntimeException("boom");
                },
                good),
            Ids.newId(),
            "agent");

    emitter.emitIterationStarted(0, 10);

    assertEquals(1, good.size(), "second sink should still receive event after first threw");
  }

  @Test
  void iterationEventsCarryTheRightFields() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");

    emitter.emitIterationStarted(7, 30);
    emitter.emitIterationCompleted(7);

    var started = (HeliosEvent.IterationStarted) sink.events().get(0);
    var completed = (HeliosEvent.IterationCompleted) sink.events().get(1);
    assertEquals(7, started.iteration());
    assertEquals(30, started.maxIterations());
    assertEquals(7, completed.iteration());
  }

  @Test
  void toolCallEventsCarryIdResultAndDuration() {
    var sink = new CollectingEventSink();
    var emitter = new EventEmitter(List.of(sink), Ids.newId(), "agent");
    var result = ToolResult.success("ok");
    emitter.emitToolCallStarted("c1", "search", Map.of("q", "x"));
    emitter.emitToolCallCompleted("c1", result, Duration.ofMillis(42));

    var started = (HeliosEvent.ToolCallStarted) sink.events().get(0);
    var completed = (HeliosEvent.ToolCallCompleted) sink.events().get(1);
    assertEquals("c1", started.toolCallId());
    assertEquals("search", started.toolName());
    assertEquals(result, completed.result());
    assertEquals(Duration.ofMillis(42), completed.took());
  }
}
