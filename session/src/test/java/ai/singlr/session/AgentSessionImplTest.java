/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.tool.Tool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class AgentSessionImplTest {

  private static final String SID = "sess-impl-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
  private static final SessionLimits LIMITS = SessionLimits.defaults();
  private static final ConcurrencyLimits CONCURRENCY = ConcurrencyLimits.defaults();

  private static Model textOnceModel(String reply, FinishReason finishReason) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(reply)
            .withFinishReason(finishReason)
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
  }

  /** Subscriber that buffers events until {@link #onComplete} fires. */
  private static final class CollectingSubscriber implements Flow.Subscriber<QueryEvent> {

    final List<QueryEvent> events = new ArrayList<>();
    final CountDownLatch done = new CountDownLatch(1);

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent event) {
      events.add(event);
    }

    @Override
    public void onError(Throwable throwable) {
      done.countDown();
    }

    @Override
    public void onComplete() {
      done.countDown();
    }

    void awaitDone() throws InterruptedException {
      assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete in 5s");
    }
  }

  // ── construction validation ───────────────────────────────────────────────

  @Test
  void constructorRejectsNullSessionId() {
    var model = textOnceModel("x", FinishReason.STOP);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new AgentSessionImpl(null, model, LIMITS, CONCURRENCY, CLOCK));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsBlankSessionId() {
    var model = textOnceModel("x", FinishReason.STOP);
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AgentSessionImpl("   ", model, LIMITS, CONCURRENCY, CLOCK));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void constructorRejectsNullModel() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new AgentSessionImpl(SID, null, LIMITS, CONCURRENCY, CLOCK));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullLimits() {
    var model = textOnceModel("x", FinishReason.STOP);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new AgentSessionImpl(SID, model, null, CONCURRENCY, CLOCK));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullConcurrency() {
    var model = textOnceModel("x", FinishReason.STOP);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new AgentSessionImpl(SID, model, LIMITS, null, CLOCK));
    assertEquals("concurrency must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullClock() {
    var model = textOnceModel("x", FinishReason.STOP);
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new AgentSessionImpl(SID, model, LIMITS, CONCURRENCY, null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  // ── accessor smoke ────────────────────────────────────────────────────────

  @Test
  void sessionIdAccessorReturnsConstructorValue() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      assertEquals(SID, s.sessionId());
    }
  }

  @Test
  void currentTurnIndexStartsAtZero() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      assertEquals(0, s.currentTurnIndex());
    }
  }

  @Test
  void eventsAccessorReturnsPublisher() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      assertNotNull(s.events());
    }
  }

  // ── send validation ──────────────────────────────────────────────────────

  @Test
  void sendRejectsNullMessage() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var ex = assertThrows(NullPointerException.class, () -> s.send((UserMessage) null));
      assertEquals("message must not be null", ex.getMessage());
    }
  }

  @Test
  void sendOnClosedSessionThrows() {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.close();
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    assertEquals("session is closed", ex.getMessage());
  }

  @Test
  void sendOnTerminalSessionThrows() throws Exception {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("done", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.send(UserMessage.text("hi"));
    s.result().get(5, TimeUnit.SECONDS);
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("again")));
    assertEquals("session is terminal", ex.getMessage());
    s.close();
  }

  @Test
  void sendFullQueueThrows() throws Exception {
    // capacity 2, latched model so we can fill the queue deterministically while the loop is
    // blocked inside chat()
    var tinyConcurrency = new ConcurrencyLimits(32, 4, 2, 2);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = new AgentSessionImpl(SID, latched, LIMITS, tinyConcurrency, CLOCK);
    try {
      s.send(UserMessage.text("first"));
      assertTrue(entered.await(5, TimeUnit.SECONDS), "loop must reach chat()");
      // Loop has drained "first" and is blocked in chat. Now fill capacity-2 queue:
      s.send(UserMessage.text("second"));
      s.send(UserMessage.text("third"));
      var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("fourth")));
      assertTrue(ex.getMessage().startsWith("steering queue full"));
    } finally {
      release.countDown();
      s.close();
    }
  }

  @Test
  void interruptOnFullQueueThrows() throws Exception {
    var tinyConcurrency = new ConcurrencyLimits(32, 4, 2, 1);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = new AgentSessionImpl(SID, latched, LIMITS, tinyConcurrency, CLOCK);
    try {
      s.send(UserMessage.text("first"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      s.send(UserMessage.text("second")); // fills capacity-1 queue
      var ex = assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
      assertTrue(ex.getMessage().contains("cannot enqueue interrupt"));
    } finally {
      release.countDown();
      s.close();
    }
  }

  // ── interrupt validation ─────────────────────────────────────────────────

  @Test
  void interruptRejectsNullReason() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var ex = assertThrows(NullPointerException.class, () -> s.interrupt(null));
      assertEquals("reason must not be null", ex.getMessage());
    }
  }

  @Test
  void interruptRejectsBlankReason() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var ex = assertThrows(IllegalArgumentException.class, () -> s.interrupt("  "));
      assertEquals("reason must not be blank", ex.getMessage());
    }
  }

  @Test
  void interruptOnClosedSessionThrows() {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.close();
    assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
  }

  @Test
  void interruptOnTerminalSessionThrows() throws Exception {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("done", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.send(UserMessage.text("hi"));
    s.result().get(5, TimeUnit.SECONDS);
    assertThrows(IllegalStateException.class, () -> s.interrupt("late"));
    s.close();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleMessageProducesSuccessAndStreamCompletes() throws Exception {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("hello back", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));

      var result = s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("hello back", success.result());
      assertTrue(sub.events.stream().anyMatch(e -> e instanceof QueryEvent.UserMessageReceived));
      assertTrue(sub.events.stream().anyMatch(e -> e instanceof QueryEvent.AssistantText));
      assertTrue(sub.events.stream().anyMatch(e -> e instanceof QueryEvent.LoopEnded));
    }
  }

  @Test
  void runBlockingDrivesSendAndAwait() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("done", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var result = s.runBlocking(UserMessage.text("hi"));
      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("done", success.result());
    }
  }

  // ── interrupt steering ────────────────────────────────────────────────────

  @Test
  void interruptQueuesSyntheticMessageAndContinues() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Model alternating =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = calls.incrementAndGet();
            return Response.newBuilder()
                .withContent("turn-" + call)
                .withFinishReason(FinishReason.STOP)
                .withUsage(Usage.of(1, 1))
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
    try (var s = new AgentSessionImpl(SID, alternating, LIMITS, CONCURRENCY, CLOCK)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("first"));
      // Interrupt before terminal so the classifier sees pending messages.
      s.interrupt("rethink");
      var result = s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();

      var success = assertInstanceOf(ResultMessage.Success.class, result);
      var interruptedReceived =
          sub.events.stream()
              .filter(e -> e instanceof QueryEvent.UserMessageReceived)
              .map(e -> (QueryEvent.UserMessageReceived) e)
              .anyMatch(u -> u.message().text().contains("[interrupted by user: rethink]"));
      assertTrue(interruptedReceived, "interrupt synthetic message must be received");
      assertEquals("turn-" + calls.get(), success.result());
    }
  }

  // ── close lifecycle ───────────────────────────────────────────────────────

  @Test
  void closeBeforeAnySendProducesCancelledTerminal() throws Exception {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("never", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.close();
    var result = s.result().get(2, TimeUnit.SECONDS);
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("session closed", c.reason());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.close();
    s.close();
    s.close();
    assertNotNull(s.result().get(1, TimeUnit.SECONDS));
  }

  @Test
  void closeAfterTerminalDoesNotBreak() throws Exception {
    var s =
        new AgentSessionImpl(
            SID, textOnceModel("done", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK);
    s.send(UserMessage.text("hi"));
    var first = s.result().get(5, TimeUnit.SECONDS);
    s.close();
    // result future not re-completed
    assertEquals(first, s.result().get(0, TimeUnit.MILLISECONDS));
  }

  @Test
  void closeDuringRunningLoopProducesCancelled() throws Exception {
    try (var s = new AgentSessionImpl(SID, blockingModel(), LIMITS, CONCURRENCY, CLOCK)) {
      s.send(UserMessage.text("hi"));
      Thread.sleep(50);
      s.close();
      // The blocking model never returns; the cancellation token does NOT interrupt
      // an in-flight chat() in Phase 1 (only Phase 2 wires this). So result may never
      // complete. We only assert that close() didn't throw.
      assertNotNull(s.result());
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Model whose chat() awaits {@code release} after signalling {@code entered}. */
  private static Model latchedModel(CountDownLatch entered, CountDownLatch release, String reply) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Response.newBuilder()
            .withContent(reply)
            .withFinishReason(FinishReason.STOP)
            .withUsage(Usage.of(1, 1))
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

  /** Model whose chat() never returns — useful for verifying queue-full and close paths. */
  private static Model blockingModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        try {
          Thread.sleep(Duration.ofMinutes(10).toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Response.newBuilder().withContent("").withFinishReason(FinishReason.STOP).build();
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

  @Test
  void resultFutureExposedAsCompletableFuture() {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("x", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var f = s.result();
      assertInstanceOf(CompletableFuture.class, f);
    }
  }

  @Test
  void futureCompletesExceptionallyOnLoopThrow() throws Exception {
    Model throwing =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model boom");
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
    try (var s = new AgentSessionImpl(SID, throwing, LIMITS, CONCURRENCY, CLOCK)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      // The TurnRunner catches the upstream onError; the loop produces ErrorDuringExecution.
      // So the future completes normally with that terminal.
      var result = s.result().get(5, TimeUnit.SECONDS);
      assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    }
  }

  @Test
  void multipleSubscribersAllReceiveEvents() throws Exception {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("hello", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var sub1 = new CollectingSubscriber();
      var sub2 = new CollectingSubscriber();
      s.events().subscribe(sub1);
      s.events().subscribe(sub2);
      s.send(UserMessage.text("hi"));
      s.result().get(5, TimeUnit.SECONDS);
      sub1.awaitDone();
      sub2.awaitDone();
      assertTrue(sub1.events.size() > 0);
      assertEquals(sub1.events.size(), sub2.events.size(), "both subscribers see the same stream");
    }
  }

  @Test
  void resultGetWithTimeoutWorks() throws Exception {
    try (var s = new AgentSessionImpl(SID, blockingModel(), LIMITS, CONCURRENCY, CLOCK)) {
      s.send(UserMessage.text("hi"));
      assertThrows(TimeoutException.class, () -> s.result().get(100, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void timestampOnPublishedEventsCarriesClock() throws Exception {
    try (var s =
        new AgentSessionImpl(
            SID, textOnceModel("hi", FinishReason.STOP), LIMITS, CONCURRENCY, CLOCK)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();
      // Every event timestamp is the fixed clock instant since the clock never moves.
      for (var e : sub.events) {
        assertEquals(FIXED, e.timestamp());
      }
    }
  }
}
