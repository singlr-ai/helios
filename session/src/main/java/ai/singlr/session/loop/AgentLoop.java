/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.Message;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.ResultMessage;
import ai.singlr.session.SerializedError;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SteeringQueue;
import ai.singlr.session.UserMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Top-level orchestrator that drives one open-ended session to a terminal {@link ResultMessage}.
 *
 * <p>Every iteration:
 *
 * <ol>
 *   <li>Drain the {@link SteeringQueue}. Pending {@link UserMessage}s become a composite assistant
 *       turn input — one {@link QueryEvent.UserMessageReceived} fires per original message; the
 *       composite is appended to history.
 *   <li>If history is still empty (no message has ever been observed and the queue was empty),
 *       terminate with {@link ResultMessage.ErrorDuringExecution} — a session must observe at least
 *       one user message before it can be steered.
 *   <li>Advance the turn counter and call {@link TurnRunner#runTurn(SessionState, SessionLimits)}.
 *   <li>Hand the outcome to {@link StopClassifier}. If it returns terminal, fire {@link
 *       HookPhase#PRE_STOP}, set the state's terminal value, emit {@link QueryEvent.LoopEnded}, and
 *       exit.
 *   <li>Otherwise iterate.
 * </ol>
 *
 * <p>Phase 1 stays text-only: the loop never invokes {@link ToolDispatch}, but holds a reference so
 * Phase 2 can fill in the tool-call dispatch without changing the constructor surface.
 *
 * <h2>Thread-safety</h2>
 *
 * One AgentLoop instance per session. {@link #run(SessionState, SessionLimits)} runs on a single
 * virtual thread. Shared collaborators ({@link TurnRunner}, {@link StopClassifier}) are
 * deliberately stateless and reusable across sessions; per-session collaborators ({@link
 * SessionState}, {@link SteeringQueue}, {@link HookRunner}, {@link ToolDispatch}) are owned by the
 * caller and disposed when the session terminates.
 */
public final class AgentLoop {

  private final TurnRunner turnRunner;
  private final StopClassifier classifier;
  private final HookRunner hookRunner;
  private final ToolDispatch toolDispatch;
  private final SteeringQueue steeringQueue;
  private final Consumer<QueryEvent> eventSink;
  private final Clock clock;

  /**
   * Build an agent loop.
   *
   * @param turnRunner the per-turn worker; non-null
   * @param classifier terminal-result classifier; non-null
   * @param hookRunner hook firing surface (no-op stub in Phase 1); non-null
   * @param toolDispatch tool dispatch surface (held but unused in Phase 1); non-null
   * @param steeringQueue per-session user-message inbox; non-null
   * @param eventSink consumer for {@link QueryEvent}s emitted by the loop; non-null
   * @param clock supplies event timestamps; non-null
   * @throws NullPointerException if any argument is null
   */
  public AgentLoop(
      TurnRunner turnRunner,
      StopClassifier classifier,
      HookRunner hookRunner,
      ToolDispatch toolDispatch,
      SteeringQueue steeringQueue,
      Consumer<QueryEvent> eventSink,
      Clock clock) {
    this.turnRunner = Objects.requireNonNull(turnRunner, "turnRunner must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    this.hookRunner = Objects.requireNonNull(hookRunner, "hookRunner must not be null");
    this.toolDispatch = Objects.requireNonNull(toolDispatch, "toolDispatch must not be null");
    this.steeringQueue = Objects.requireNonNull(steeringQueue, "steeringQueue must not be null");
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Run the loop to terminal.
   *
   * @param state per-session mutable state; non-null
   * @param limits per-session limits; non-null
   * @return the terminal {@link ResultMessage}; never null
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public ResultMessage run(SessionState state, SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    try {
      return runUnsafe(state, limits);
    } catch (Throwable t) {
      return crashTerminate(state, t);
    }
  }

  private static ResultMessage crashTerminate(SessionState state, Throwable t) {
    var failure =
        new ResultMessage.ErrorDuringExecution(
            state.sessionId(),
            ai.singlr.session.SerializedError.of(t),
            state.usage(),
            state.cost(),
            state.elapsed());
    state.setTerminal(failure);
    return failure;
  }

  private ResultMessage runUnsafe(SessionState state, SessionLimits limits) {
    while (true) {
      drainAndAppend(state);
      if (state.historySnapshot().isEmpty()) {
        return terminate(state, emptyHistoryError(state));
      }
      state.beginTurn();
      var outcome = turnRunner.runTurn(state, limits);
      var terminal =
          classifier.classify(
              state,
              limits,
              outcome.finishReason(),
              outcome.assistantContent(),
              steeringQueue.size() > 0);
      if (terminal.isPresent()) {
        return terminate(state, terminal.orElseThrow());
      }
    }
  }

  private void drainAndAppend(SessionState state) {
    var drained = steeringQueue.drain();
    if (drained.isEmpty()) {
      return;
    }
    for (var msg : drained) {
      eventSink.accept(
          new QueryEvent.UserMessageReceived(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), msg));
      hookRunner.fire(HookPhase.ON_USER_MESSAGE);
    }
    state.appendMessage(Message.user(composeContent(drained)));
  }

  private static String composeContent(List<UserMessage> messages) {
    if (messages.size() == 1) {
      return messages.get(0).text();
    }
    var joined = new StringBuilder();
    joined.append("[messages composed: ").append(messages.size()).append("]\n");
    for (var i = 0; i < messages.size(); i++) {
      if (i > 0) {
        joined.append("\n");
      }
      joined.append(messages.get(i).text());
    }
    return joined.toString();
  }

  private ResultMessage terminate(SessionState state, ResultMessage result) {
    hookRunner.fire(HookPhase.PRE_STOP);
    state.setTerminal(result);
    eventSink.accept(
        new QueryEvent.LoopEnded(
            state.sessionId(), state.currentTurnIndex(), clock.instant(), result));
    hookRunner.fire(HookPhase.ON_STREAM_EVENT);
    return result;
  }

  private ResultMessage emptyHistoryError(SessionState state) {
    return new ResultMessage.ErrorDuringExecution(
        state.sessionId(),
        SerializedError.of(
            "EmptyHistory",
            "AgentLoop.run requires at least one user message in the steering queue before "
                + "starting"),
        state.usage(),
        state.cost(),
        state.elapsed());
  }

  /** Internal accessor for tests so they can verify clock injection. */
  Instant nowForTests() {
    return clock.instant();
  }

  /**
   * The bound {@link ToolDispatch}. Phase 1 holds it for forward-compat; Phase 2 wires it through
   * {@link TurnRunner} to dispatch tool calls.
   *
   * @return the tool dispatch instance
   */
  public ToolDispatch toolDispatch() {
    return toolDispatch;
  }
}
