/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.ask.AskUserQuestionResponse;
import ai.singlr.session.execution.ExecutionCapabilities;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.execution.ExecutionRequest;
import ai.singlr.session.execution.ExecutionResult;
import ai.singlr.session.execution.SessionStartOutcome;
import ai.singlr.session.hooks.PreStopHook;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class AgentSessionImplTest {

  private static final String SID = "sess-impl-1";
  private static final Instant FIXED = Instant.parse("2026-05-14T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

  private static AgentSession buildSession(Model model) {
    return buildSession(model, ConcurrencyLimits.defaults());
  }

  private static AgentSession buildSession(Model model, ConcurrencyLimits concurrency) {
    return AgentSession.create(
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId(SID)
            .withConcurrencyLimits(concurrency)
            .withClock(CLOCK)
            .build());
  }

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
  void constructorRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> new AgentSessionImpl(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryRejectsNullOptions() {
    var ex = assertThrows(NullPointerException.class, () -> AgentSession.create(null));
    assertEquals("options must not be null", ex.getMessage());
  }

  @Test
  void createFactoryReturnsAgentSession() {
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("x", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .build())) {
      assertEquals(SID, s.sessionId());
    }
  }

  // ── accessor smoke ────────────────────────────────────────────────────────

  @Test
  void sessionIdAccessorReturnsOptionsValue() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(SID, s.sessionId());
    }
  }

  @Test
  void currentTurnIndexStartsAtZero() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertEquals(0, s.currentTurnIndex());
    }
  }

  @Test
  void eventsAccessorReturnsPublisher() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      assertNotNull(s.events());
    }
  }

  // ── send validation ──────────────────────────────────────────────────────

  @Test
  void sendRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.send((UserMessage) null));
      assertEquals("message must not be null", ex.getMessage());
    }
  }

  @Test
  void sendOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    assertEquals("session is closed", ex.getMessage());
  }

  @Test
  void sendOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    s.result().get(5, TimeUnit.SECONDS);
    var ex = assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("again")));
    assertEquals("session is terminal", ex.getMessage());
    s.close();
  }

  @Test
  void sendFullQueueThrows() throws Exception {
    var tinyConcurrency = new ConcurrencyLimits(32, 4, 2, 2);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    Model latched = latchedModel(entered, release, "ok");
    var s = buildSession(latched, tinyConcurrency);
    try {
      s.send(UserMessage.text("first"));
      assertTrue(entered.await(5, TimeUnit.SECONDS), "loop must reach chat()");
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
    var s = buildSession(latched, tinyConcurrency);
    try {
      s.send(UserMessage.text("first"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      s.send(UserMessage.text("second"));
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
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(NullPointerException.class, () -> s.interrupt(null));
      assertEquals("reason must not be null", ex.getMessage());
    }
  }

  @Test
  void interruptRejectsBlankReason() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var ex = assertThrows(IllegalArgumentException.class, () -> s.interrupt("  "));
      assertEquals("reason must not be blank", ex.getMessage());
    }
  }

  @Test
  void interruptOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    assertThrows(IllegalStateException.class, () -> s.interrupt("nope"));
  }

  @Test
  void interruptOnTerminalSessionThrows() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    s.result().get(5, TimeUnit.SECONDS);
    assertThrows(IllegalStateException.class, () -> s.interrupt("late"));
    s.close();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void singleMessageProducesSuccessAndStreamCompletes() throws Exception {
    try (var s = buildSession(textOnceModel("hello back", FinishReason.STOP))) {
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
    try (var s = buildSession(textOnceModel("done", FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"));
      var success = assertInstanceOf(ResultMessage.Success.class, result);
      assertEquals("done", success.result());
    }
  }

  // ── interrupt steering ────────────────────────────────────────────────────

  @Test
  void interruptQueuesSyntheticMessageAndContinues() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var firstTurnEntered = new CountDownLatch(1);
    var firstTurnRelease = new CountDownLatch(1);
    Model alternating =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var call = calls.incrementAndGet();
            if (call == 1) {
              // Hold the first turn until the test has had a chance to queue interrupt().
              // Otherwise CI-fast runners can complete the first turn before interrupt arrives,
              // which sends the session terminal and makes interrupt() throw "session is terminal".
              firstTurnEntered.countDown();
              try {
                firstTurnRelease.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
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
    try (var s = buildSession(alternating)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("first"));
      assertTrue(
          firstTurnEntered.await(5, TimeUnit.SECONDS),
          "expected the first model.chat to be reached within 5s");
      s.interrupt("rethink");
      firstTurnRelease.countDown();
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
    var s = buildSession(textOnceModel("never", FinishReason.STOP));
    s.close();
    var result = s.result().get(2, TimeUnit.SECONDS);
    var c = assertInstanceOf(ResultMessage.Cancelled.class, result);
    assertEquals("session closed", c.reason());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    var s = buildSession(textOnceModel("x", FinishReason.STOP));
    s.close();
    s.close();
    s.close();
    assertNotNull(s.result().get(1, TimeUnit.SECONDS));
  }

  @Test
  void closeAfterTerminalDoesNotBreak() throws Exception {
    var s = buildSession(textOnceModel("done", FinishReason.STOP));
    s.send(UserMessage.text("hi"));
    var first = s.result().get(5, TimeUnit.SECONDS);
    s.close();
    assertEquals(first, s.result().get(0, TimeUnit.MILLISECONDS));
  }

  @Test
  void closeDuringRunningLoopProducesCancelled() throws Exception {
    try (var s = buildSession(blockingModel())) {
      s.send(UserMessage.text("hi"));
      Thread.sleep(50);
      s.close();
      assertNotNull(s.result());
    }
  }

  @Test
  void closeBeforeAnySendShutsDownPublisherExecutor() {
    var s = (AgentSessionImpl) buildSession(textOnceModel("x", FinishReason.STOP));
    var executor = s.publisherExecutorForTests();
    assertTrue(!executor.isShutdown(), "executor live before close()");
    s.close();
    assertTrue(executor.isShutdown(), "executor shut down by close()");
    assertTrue(executor.isTerminated(), "executor terminated by close()");
  }

  @Test
  void naturalLoopTerminationShutsDownPublisherExecutor() throws Exception {
    var s = (AgentSessionImpl) buildSession(textOnceModel("done", FinishReason.STOP));
    var executor = s.publisherExecutorForTests();
    s.send(UserMessage.text("hi"));
    s.result().get(5, TimeUnit.SECONDS);
    // runLoop's finally block has fired by the time the future completes; the executor close
    // happens in the same finally so it is guaranteed-shut-down at this point.
    assertTrue(executor.isShutdown(), "executor shut down after natural termination");
    assertTrue(executor.isTerminated(), "executor terminated after natural termination");
  }

  @Test
  void resultFutureExposedAsCompletableFuture() {
    try (var s = buildSession(textOnceModel("x", FinishReason.STOP))) {
      var f = s.result();
      assertInstanceOf(CompletableFuture.class, f);
    }
  }

  @Test
  void modelThatThrowsSynchronouslyProducesErrorTerminal() throws Exception {
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
    try (var s = buildSession(throwing)) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      var result = s.result().get(5, TimeUnit.SECONDS);
      assertInstanceOf(ResultMessage.ErrorDuringExecution.class, result);
    }
  }

  @Test
  void errorEscapingAgentLoopSettlesFutureExceptionallyInsteadOfHanging() {
    // AgentLoop.run catches Exception (not Throwable); HookRegistry catches RuntimeException (not
    // Throwable). So an Error subtype thrown from a hook escapes both defensive catches and lands
    // in AgentSessionImpl.runLoop's outer catch. Without that catch, resultFuture never completes
    // and every caller blocked on result().join() hangs indefinitely.
    PreStopHook erroring =
        (response, ctx) -> {
          throw new AssertionError("simulated unrecoverable error");
        };
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withHook(erroring)
                .build())) {
      s.send(UserMessage.text("hi"));
      var ex =
          assertThrows(
              CompletionException.class, () -> s.result().orTimeout(5, TimeUnit.SECONDS).join());
      assertInstanceOf(AssertionError.class, ex.getCause());
      assertEquals("simulated unrecoverable error", ex.getCause().getMessage());
    }
  }

  // ── execution-provider lifecycle (onSessionStart / onSessionEnd) ─────────

  /** Stub provider that observes start / end and can be configured to refuse or throw. */
  private static final class LifecycleProvider implements ExecutionProvider {
    final AtomicBoolean startSeen = new AtomicBoolean();
    final AtomicBoolean endSeen = new AtomicBoolean();
    SessionStartOutcome startOutcome = SessionStartOutcome.accept();
    RuntimeException throwOnStart;
    RuntimeException throwOnEnd;

    @Override
    public ExecutionCapabilities capabilities() {
      return ExecutionCapabilities.newBuilder().build();
    }

    @Override
    public SessionStartOutcome onSessionStart(SessionContext ctx) {
      startSeen.set(true);
      if (throwOnStart != null) {
        throw throwOnStart;
      }
      return startOutcome;
    }

    @Override
    public void onSessionEnd(SessionContext ctx) {
      endSeen.set(true);
      if (throwOnEnd != null) {
        throw throwOnEnd;
      }
    }

    @Override
    public CompletionStage<ExecutionResult> execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      throw new AssertionError("not used");
    }
  }

  @Test
  void providerRefuseProducesErrorProviderUnavailableTerminal() throws Exception {
    var provider = new LifecycleProvider();
    provider.startOutcome = SessionStartOutcome.refuse("pool saturated");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      assertTrue(provider.startSeen.get());
      var terminal = s.result().get(2, TimeUnit.SECONDS);
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
      assertEquals("pool saturated", err.reason());
      assertEquals("LifecycleProvider", err.providerName());
      // send() against a refused (terminal) session throws.
      assertThrows(IllegalStateException.class, () -> s.send(UserMessage.text("hi")));
    }
    // onSessionEnd never fires when the provider refused at start.
    assertFalse(provider.endSeen.get());
  }

  @Test
  void providerOnSessionStartRuntimeExceptionProducesErrorProviderUnavailable() throws Exception {
    var provider = new LifecycleProvider();
    provider.throwOnStart = new RuntimeException("auth failed");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      var terminal = s.result().get(2, TimeUnit.SECONDS);
      var err = assertInstanceOf(ResultMessage.ErrorProviderUnavailable.class, terminal);
      assertTrue(err.reason().contains("auth failed"));
      assertTrue(err.reason().contains("RuntimeException"));
    }
    assertFalse(provider.endSeen.get());
  }

  @Test
  void providerOnSessionEndFiresOnSuccessfulTerminal() throws Exception {
    var provider = new LifecycleProvider();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      assertTrue(provider.startSeen.get());
      s.send(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, s.result().get(5, TimeUnit.SECONDS));
    }
    assertTrue(provider.endSeen.get(), "onSessionEnd must fire after successful terminal");
  }

  @Test
  void providerOnSessionEndFiresOnPreStartClose() throws Exception {
    // Loop never starts (no send/interrupt) — close() is the only signal.
    var provider = new LifecycleProvider();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("unused", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      // intentionally no send — just close
      var _unused = s;
    }
    assertTrue(provider.endSeen.get(), "onSessionEnd must fire on pre-start close");
  }

  @Test
  void providerOnSessionEndThrowingRuntimeExceptionIsSwallowed() throws Exception {
    var provider = new LifecycleProvider();
    provider.throwOnEnd = new RuntimeException("end-cleanup-boom");
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("done", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withExecutionProvider(provider)
                .build())) {
      s.send(UserMessage.text("hi"));
      // Terminal must still be Success — onSessionEnd exception swallowed and logged.
      assertInstanceOf(ResultMessage.Success.class, s.result().get(5, TimeUnit.SECONDS));
    }
    assertTrue(provider.endSeen.get());
  }

  @Test
  void multipleSubscribersAllReceiveEvents() throws Exception {
    try (var s = buildSession(textOnceModel("hello", FinishReason.STOP))) {
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
    try (var s = buildSession(blockingModel())) {
      s.send(UserMessage.text("hi"));
      assertThrows(TimeoutException.class, () -> s.result().get(100, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void timestampOnPublishedEventsCarriesClock() throws Exception {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var sub = new CollectingSubscriber();
      s.events().subscribe(sub);
      s.send(UserMessage.text("hi"));
      s.result().get(5, TimeUnit.SECONDS);
      sub.awaitDone();
      for (var e : sub.events) {
        assertEquals(FIXED, e.timestamp());
      }
    }
  }

  // ── typed runBlocking ────────────────────────────────────────────────────

  /** Output record used by the typed-runBlocking tests below. */
  public record TypedAnswer(String name, int score) {}

  @Test
  void typedRunBlockingParsesFinalAssistantTextAgainstSchema() {
    var json = "{\"name\":\"alice\",\"score\":42}";
    try (var s = buildSession(textOnceModel(json, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("alice", result.name());
      assertEquals(42, result.score());
    }
  }

  @Test
  void typedRunBlockingToleratesMarkdownFences() {
    var fenced = "```json\n{\"name\":\"bob\",\"score\":7}\n```";
    try (var s = buildSession(textOnceModel(fenced, FinishReason.STOP))) {
      var result = s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class));
      assertEquals("bob", result.name());
      assertEquals(7, result.score());
    }
  }

  @Test
  void typedRunBlockingRejectsNullMessage() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(null, OutputSchema.of(TypedAnswer.class)));
    }
  }

  @Test
  void typedRunBlockingRejectsNullSchema() {
    try (var s = buildSession(textOnceModel("{}", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.runBlocking(UserMessage.text("hi"), (OutputSchema<?>) null));
    }
  }

  @Test
  void typedRunBlockingThrowsOnNonSuccessTerminal() {
    // CONTENT_FILTER produces a Refusal terminal — typed runBlocking has nothing to parse.
    try (var s = buildSession(textOnceModel("model refusal", FinishReason.CONTENT_FILTER))) {
      var ex =
          assertThrows(
              IllegalStateException.class,
              () -> s.runBlocking(UserMessage.text("hi"), OutputSchema.of(TypedAnswer.class)));
      assertTrue(ex.getMessage().contains("Refusal"));
    }
  }

  // ── maxBudgetMicroUsd integration ────────────────────────────────────────

  @Test
  void costCalculatorAccumulatesAndExceedingBudgetProducesErrorMaxBudgetTerminal() {
    // Pricing: $1.00 / Mtok input, $1.00 / Mtok output. One turn of 1M+1M tokens => $2.00.
    // Budget: $1.50. Expectation: ErrorMaxBudgetUsd terminal with microUsdSpent == 2_000_000.
    var calc =
        CostCalculator.staticTable(
            Map.of("test", CostCalculator.Pricing.ofUsdPerMillion(1.0, 1.0)));
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(1_500_000L).build();
    var bigUsage = Usage.of(1_000_000, 1_000_000);
    Model billy =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("here")
                .withFinishReason(FinishReason.STOP)
                .withUsage(bigUsage)
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
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(billy)
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .withCostCalculator(calc)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      var budget = assertInstanceOf(ResultMessage.ErrorMaxBudgetUsd.class, terminal);
      assertEquals(2_000_000L, budget.microUsdSpent());
      assertEquals(2_000_000L, budget.cost().microUsd());
    }
  }

  @Test
  void defaultCostCalculatorIsZeroSoBudgetNeverFires() {
    // No withCostCalculator(...) call. Even with a tight budget, cost stays $0 and the session
    // completes normally — this is the opt-in contract.
    var limits = SessionLimits.newBuilder().withMaxBudgetMicroUsd(10_000L).build();
    try (var s =
        AgentSession.create(
            SessionOptions.newBuilder()
                .withModel(textOnceModel("hello", FinishReason.STOP))
                .withSessionId(SID)
                .withClock(CLOCK)
                .withLimits(limits)
                .build())) {
      var terminal = s.runBlocking(UserMessage.text("hi"));
      assertInstanceOf(ResultMessage.Success.class, terminal);
      assertEquals(CostEstimate.zero(), terminal.cost());
    }
  }

  // ── answer() validation ──────────────────────────────────────────────────

  @Test
  void answerRejectsNullQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          NullPointerException.class,
          () -> s.answer(null, AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsBlankQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> s.answer("  ", AskUserQuestionResponse.single("q", "ok")));
    }
  }

  @Test
  void answerRejectsNullResponse() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      assertThrows(NullPointerException.class, () -> s.answer("q-1", null));
    }
  }

  @Test
  void answerRejectsMismatchedResponseQuestionId() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-1", AskUserQuestionResponse.single("q-OTHER", "x")));
      assertTrue(ex.getMessage().contains("does not match"));
    }
  }

  @Test
  void answerOnClosedSessionThrows() {
    var s = buildSession(textOnceModel("hi", FinishReason.STOP));
    s.close();
    assertThrows(
        IllegalStateException.class,
        () -> s.answer("q-1", AskUserQuestionResponse.single("q-1", "ok")));
  }

  @Test
  void answerForUnknownQuestionIdThrows() {
    try (var s = buildSession(textOnceModel("hi", FinishReason.STOP))) {
      var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> s.answer("q-unknown", AskUserQuestionResponse.single("q-unknown", "ok")));
      assertTrue(ex.getMessage().contains("no pending question"));
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
}
