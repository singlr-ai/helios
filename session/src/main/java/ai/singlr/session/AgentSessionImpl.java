/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.session.loop.AgentLoop;
import ai.singlr.session.loop.HookRunner;
import ai.singlr.session.loop.SessionState;
import ai.singlr.session.loop.StopClassifier;
import ai.singlr.session.loop.ToolDispatch;
import ai.singlr.session.loop.TurnRunner;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link AgentSession} implementation.
 *
 * <p>One instance per session. Builds the loop substrate ({@link SessionState}, {@link
 * SteeringQueue}, {@link HookRunner}, {@link ToolDispatch}, {@link TurnRunner}, {@link
 * StopClassifier}, {@link AgentLoop}) in the constructor; defers starting the agent-loop virtual
 * thread until the first {@link #send(UserMessage)} or {@link #interrupt(String)} call so
 * subscribers attached between construction and first send observe every event.
 *
 * <h2>Event delivery</h2>
 *
 * Events flow through a {@link SubmissionPublisher} sized at the JDK default 256-item buffer per
 * subscriber. Subscriber delivery uses a per-session virtual-thread executor; a slow subscriber
 * back-pressures the agent loop via {@code submit} rather than silently dropping events.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. Producer threads (HTTP, UI) call {@link #send}/{@link #interrupt}/{@link #close}
 * concurrently; the agent loop runs on a dedicated virtual thread. Atomic flags coordinate
 * lifecycle. The loop is the only writer to {@link SessionState}'s mutable fields aside from {@code
 * close()}'s pre-start terminal write, which is guarded by the same compare-and-set as the loop
 * launch — at most one of the two paths executes.
 */
public final class AgentSessionImpl implements AgentSession {

  private static final int PUBLISHER_BUFFER = 256;

  private final String sessionId;
  private final SessionState state;
  private final SteeringQueue steeringQueue;
  private final SessionLimits limits;
  private final SubmissionPublisher<QueryEvent> publisher;
  private final AgentLoop loop;
  private final CompletableFuture<ResultMessage> resultFuture = new CompletableFuture<>();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Build a session from a composition record.
   *
   * @param options the configuration bundle; non-null
   * @throws NullPointerException if {@code options} is null
   */
  public AgentSessionImpl(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    this.sessionId = options.sessionId();
    this.limits = options.limits();
    var concurrency = options.concurrency();
    var clock = options.clock();
    var cancellation = new CancellationToken();
    this.state = new SessionState(sessionId, cancellation, clock);
    this.steeringQueue = new SteeringQueue(concurrency.maxQueuedUserMessages());
    var hookRunner = HookRunner.empty();
    var toolDispatch = new ToolDispatch(concurrency);
    this.publisher =
        new SubmissionPublisher<>(Executors.newVirtualThreadPerTaskExecutor(), PUBLISHER_BUFFER);
    var turnRunner = new TurnRunner(options.model(), hookRunner, publisher::submit, clock);
    this.loop =
        new AgentLoop(
            turnRunner,
            new StopClassifier(),
            hookRunner,
            toolDispatch,
            steeringQueue,
            publisher::submit,
            clock);
  }

  @Override
  public void send(UserMessage message) {
    Objects.requireNonNull(message, "message must not be null");
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    if (!steeringQueue.offer(message)) {
      throw new IllegalStateException(
          "steering queue full at capacity " + steeringQueue.capacity());
    }
    startIfNeeded();
  }

  @Override
  public void interrupt(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    if (closed.get()) {
      throw new IllegalStateException("session is closed");
    }
    if (state.isTerminal()) {
      throw new IllegalStateException("session is terminal");
    }
    var synthetic = UserMessage.text("[interrupted by user: " + reason + "]");
    if (!steeringQueue.offer(synthetic)) {
      throw new IllegalStateException(
          "steering queue full at capacity "
              + steeringQueue.capacity()
              + " — cannot enqueue interrupt");
    }
    startIfNeeded();
  }

  @Override
  public Flow.Publisher<QueryEvent> events() {
    return publisher;
  }

  @Override
  public CompletableFuture<ResultMessage> result() {
    return resultFuture;
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public long currentTurnIndex() {
    return state.currentTurnIndex();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    state.cancellation().cancel("session closed");
    // If the loop has never started, complete the future ourselves so result().get() doesn't
    // hang. If the loop is running, it will observe the cancellation on its next iteration and
    // complete the future via runLoop's finally block — we leave it alone here.
    if (started.compareAndSet(false, true)) {
      var preStartResult =
          new ResultMessage.Cancelled(
              sessionId, "session closed", state.usage(), state.cost(), state.elapsed());
      state.setTerminal(preStartResult);
      resultFuture.complete(preStartResult);
      publisher.close();
    }
  }

  private void startIfNeeded() {
    if (started.compareAndSet(false, true)) {
      Thread.ofVirtual().name("helios-agent-loop-" + sessionId).start(this::runLoop);
    }
  }

  private void runLoop() {
    try {
      var result = loop.run(state, limits);
      resultFuture.complete(result);
    } finally {
      publisher.close();
    }
  }
}
