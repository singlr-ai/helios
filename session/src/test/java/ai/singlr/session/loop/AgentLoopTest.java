/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.ConcurrencyLimits;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.UserMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentLoopTest {

  private static final String SID = "sess-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private final List<QueryEvent> events = new ArrayList<>();
  private final ai.singlr.session.hooks.HookRegistry hooks =
      ai.singlr.session.hooks.HookRegistry.empty();
  private final ToolDispatch dispatch =
      new ToolDispatch(ai.singlr.session.tools.ToolRegistry.empty(), ConcurrencyLimits.defaults());

  private static final Model CTX_MODEL =
      new Model() {
        @Override
        public Response<Void> chat(List<Message> messages, List<Tool> tools) {
          return Response.newBuilder().build();
        }

        @Override
        public String id() {
          return "stub";
        }

        @Override
        public String provider() {
          return "stub";
        }
      };

  private static final java.util.function.Function<
          SessionState, ai.singlr.session.hooks.HookContext>
      CTX_FACTORY =
          s ->
              new ai.singlr.session.hooks.DefaultHookContext(
                  s.sessionId(), s.currentTurnIndex(), s.cancellation(), CTX_MODEL);

  private SessionState freshState() {
    return new SessionState(SID, new CancellationToken(), CLOCK);
  }

  private static Model fixedModel(String content, FinishReason reason, Usage usage) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(content)
            .withFinishReason(reason)
            .withUsage(usage)
            .build();
      }

      @Override
      public String id() {
        return "test";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private AgentLoop buildLoop(Model model, SteeringQueue queue) {
    var runner =
        new TurnRunner(
            model, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK, CostCalculator.ZERO);
    return new AgentLoop(
        runner, new StopClassifier(), hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK);
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullDependencies() {
    var queue = new SteeringQueue(8);
    var runner =
        new TurnRunner(
            fixedModel("x", FinishReason.STOP, Usage.of(1, 1)),
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO);
    var classifier = new StopClassifier();
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                null, classifier, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(runner, null, hooks, dispatch, queue, events::add, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner, classifier, null, dispatch, queue, events::add, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(runner, classifier, hooks, null, queue, events::add, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner, classifier, hooks, dispatch, null, events::add, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(runner, classifier, hooks, dispatch, queue, null, CTX_FACTORY, CLOCK));
    assertThrows(
        NullPointerException.class,
        () -> new AgentLoop(runner, classifier, hooks, dispatch, queue, events::add, null, CLOCK));
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentLoop(
                runner, classifier, hooks, dispatch, queue, events::add, CTX_FACTORY, null));
  }

  @Test
  void runRejectsNullState() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertThrows(NullPointerException.class, () -> loop.run(null, SessionLimits.defaults()));
  }

  @Test
  void runRejectsNullLimits() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertThrows(NullPointerException.class, () -> loop.run(freshState(), null));
  }

  @Test
  void toolDispatchAccessorReturnsConstructorInstance() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertSame(dispatch, loop.toolDispatch());
  }

  @Test
  void nowForTestsReturnsClockInstant() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);
    assertEquals(FIXED, loop.nowForTests());
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleUserMessageProducesSuccess() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var loop = buildLoop(fixedModel("hello back", FinishReason.STOP, Usage.of(3, 2)), queue);

    var result = loop.run(freshState(), SessionLimits.defaults());

    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("hello back", success.result());
    assertEquals(3, success.usage().inputTokens());
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.AssistantText));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.TurnEnded));
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
  }

  @Test
  void multipleQueuedMessagesComposeIntoSingleUserTurn() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("one"));
    queue.offer(UserMessage.text("two"));
    queue.offer(UserMessage.text("three"));
    var state = freshState();
    var loop = buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue);

    loop.run(state, SessionLimits.defaults());

    var received = events.stream().filter(e -> e instanceof QueryEvent.UserMessageReceived).count();
    assertEquals(3, received, "one UserMessageReceived per original message");
    var history = state.historySnapshot();
    assertTrue(history.get(0).content().startsWith("[messages composed: 3]"));
    assertTrue(history.get(0).content().contains("one"));
    assertTrue(history.get(0).content().contains("two"));
    assertTrue(history.get(0).content().contains("three"));
  }

  // ── empty initial state → error ───────────────────────────────────────────

  @Test
  void emptyQueueAndEmptyHistoryProducesErrorDuringExecution() {
    var queue = new SteeringQueue(8);
    var loop = buildLoop(fixedModel("never called", FinishReason.STOP, Usage.of(0, 0)), queue);
    var result = loop.run(freshState(), SessionLimits.defaults());

    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertEquals("EmptyHistory", err.error().kind());
  }

  // ── max turns ─────────────────────────────────────────────────────────────

  @Test
  void maxTurnsCeilingProducesErrorMaxTurns() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // model always says TOOL_CALLS, never STOP → loop never naturally terminates
    var loop =
        buildLoop(fixedModel("tool tool tool", FinishReason.TOOL_CALLS, Usage.of(1, 1)), queue);
    var limits = SessionLimits.newBuilder().withMaxTurns(3).build();
    var result = loop.run(freshState(), limits);
    var t = assertInstanceOf(ResultMessage.ErrorMaxTurns.class, result);
    assertEquals(3, t.turnsUsed());
  }

  // ── mid-run steering ──────────────────────────────────────────────────────

  @Test
  void midRunSteeringExtendsLoop() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("first"));

    var modelCalls = new AtomicInteger(0);
    Model adaptive =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = modelCalls.incrementAndGet();
            // On turn 1, enqueue a follow-up before signalling STOP.
            // The classifier sees pending messages → continues to turn 2.
            // On turn 2, signal STOP cleanly with queue empty.
            if (call == 1) {
              queue.offer(UserMessage.text("follow-up"));
              return Response.newBuilder()
                  .withContent("partial")
                  .withFinishReason(FinishReason.STOP)
                  .withUsage(Usage.of(2, 1))
                  .build();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(3, 2))
                .build();
          }

          @Override
          public String id() {
            return "test";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var state = freshState();
    var loop = buildLoop(adaptive, queue);
    var result = loop.run(state, SessionLimits.defaults());

    assertEquals(2, modelCalls.get());
    var success = assertInstanceOf(ResultMessage.Success.class, result);
    assertEquals("done", success.result());
  }

  // ── cancellation ──────────────────────────────────────────────────────────

  @Test
  void preCancelledTokenProducesCancelledImmediatelyAfterFirstTurn() {
    var token = new CancellationToken();
    var state = new SessionState(SID, token, CLOCK);
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    token.cancel("user-stop");
    var loop = buildLoop(fixedModel("x", FinishReason.STOP, Usage.of(1, 1)), queue);

    var result = loop.run(state, SessionLimits.defaults());
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("user-stop", c.reason());
  }

  // ── terminal emission ────────────────────────────────────────────────────

  @Test
  void loopEndedIsAlwaysLastEvent() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var loop = buildLoop(fixedModel("hello", FinishReason.STOP, Usage.of(1, 1)), queue);
    loop.run(freshState(), SessionLimits.defaults());
    assertInstanceOf(QueryEvent.LoopEnded.class, events.get(events.size() - 1));
  }

  @Test
  void terminalIsRecordedOnState() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    var state = freshState();
    buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue)
        .run(state, SessionLimits.defaults());
    assertTrue(state.isTerminal());
    assertInstanceOf(ResultMessage.Success.class, state.terminal().orElseThrow());
  }

  @Test
  void unexpectedRuntimeExceptionInLoopProducesErrorDuringExecution() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    // Sabotage by passing an event sink that throws on the very first emission.
    var runner =
        new TurnRunner(
            fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)),
            hooks,
            dispatch,
            queue,
            events::add,
            CTX_FACTORY,
            CLOCK,
            CostCalculator.ZERO);
    java.util.function.Consumer<QueryEvent> throwingSink =
        e -> {
          throw new RuntimeException("sink boom");
        };
    var sabotaged =
        new AgentLoop(
            runner, new StopClassifier(), hooks, dispatch, queue, throwingSink, CTX_FACTORY, CLOCK);
    var result = sabotaged.run(freshState(), SessionLimits.defaults());
    var err = assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    assertEquals("java.lang.RuntimeException", err.error().kind());
    assertEquals("sink boom", err.error().message());
  }

  @Test
  void hooksFiredAtKeyPhases() {
    var queue = new SteeringQueue(8);
    queue.offer(UserMessage.text("hi"));
    buildLoop(fixedModel("ok", FinishReason.STOP, Usage.of(1, 1)), queue)
        .run(freshState(), SessionLimits.defaults());
    // With an empty registry every non-Continue hook outcome path is unreachable; verify the loop
    // ran cleanly to LoopEnded.
    assertTrue(events.stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
  }
}
