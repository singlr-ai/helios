/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelChunk;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolVisibilityContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives one model turn end-to-end: subscribes to {@link Model#chatStream(List, List,
 * ai.singlr.core.runtime.CancellationToken) Model.chatStream}, translates {@link ModelChunk}s into
 * the appropriate {@link QueryEvent}s on the event sink, accumulates assistant text + usage +
 * tool-call metadata, dispatches any tool calls the model emitted via {@link ToolDispatch}, appends
 * the assistant and per-tool messages to {@link SessionState} history, and returns a {@link
 * TurnOutcome} the agent loop hands to {@link StopClassifier}.
 *
 * <h2>Tool dispatch</h2>
 *
 * Tool calls accumulate during the stream via {@link ai.singlr.core.model.ModelChunk.ToolUseStop
 * ToolUseStop} chunks. After the stream completes the runner appends one assistant message carrying
 * content + tool calls, then dispatches each call serially via {@link
 * ToolDispatch#dispatch(ToolCall, ai.singlr.core.runtime.CancellationToken)} and appends a {@code
 * tool}-role message per result. Hooks (PreToolUse / PostToolUse) wire in here in part 2c.2.
 *
 * <p>If any tool calls were produced this turn, the runner forces the outcome's finish reason to
 * {@link FinishReason#TOOL_CALLS} so {@link StopClassifier} continues the loop — the next turn sees
 * the tool results and produces a follow-up assistant message.
 *
 * <h2>Stream synchronisation</h2>
 *
 * The runner subscribes synchronously via a single-shot {@link CountDownLatch}; {@link
 * #runTurn(SessionState, ai.singlr.session.SessionLimits)} blocks the calling thread until the
 * publisher emits {@code onComplete} or {@code onError}. The default {@code chatStream} impl
 * completes synchronously inside {@code request()}; real provider overrides complete asynchronously
 * on a background thread, but the wait shape is identical.
 *
 * <h2>Errors</h2>
 *
 * Any {@code onError} signal from the publisher produces a {@link TurnOutcome} with {@link
 * FinishReason#ERROR}; {@code assistantContent} carries the throwable's message so {@link
 * StopClassifier} can attribute the failure to {@link
 * ai.singlr.session.ResultMessage.ErrorDuringExecution}. The error-path turn does not append an
 * assistant message to history — partial content from a failed turn would corrupt the next call.
 *
 * <h2>Thread-safety</h2>
 *
 * One instance is safe to share across many sessions: it has no mutable state of its own. Each
 * {@link #runTurn(SessionState, ai.singlr.session.SessionLimits)} invocation creates its own
 * subscriber state.
 */
public final class TurnRunner {

  private final Model model;
  private final HookRunner hookRunner;
  private final ToolDispatch toolDispatch;
  private final Consumer<QueryEvent> eventSink;
  private final Clock clock;

  /**
   * Build a turn runner.
   *
   * @param model the model providing {@link Model#chatStream(List, List,
   *     ai.singlr.core.runtime.CancellationToken)}; non-null
   * @param hookRunner the hook runner to fire {@link HookPhase#PRE_MODEL_TURN} / {@link
   *     HookPhase#POST_MODEL_TURN} / {@link HookPhase#ON_STREAM_EVENT} hooks; non-null
   * @param toolDispatch dispatcher for tool calls the model emits; non-null
   * @param eventSink consumer for {@link QueryEvent}s emitted during the turn; non-null
   * @param clock clock supplying event timestamps; non-null
   * @throws NullPointerException if any argument is null
   */
  public TurnRunner(
      Model model,
      HookRunner hookRunner,
      ToolDispatch toolDispatch,
      Consumer<QueryEvent> eventSink,
      Clock clock) {
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.hookRunner = Objects.requireNonNull(hookRunner, "hookRunner must not be null");
    this.toolDispatch = Objects.requireNonNull(toolDispatch, "toolDispatch must not be null");
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Run one turn against the model and return its outcome.
   *
   * @param state the session state (history read, history+usage written); non-null
   * @param limits the session limits; non-null
   * @return the outcome of the turn
   * @throws NullPointerException if {@code state} or {@code limits} is null
   */
  public TurnOutcome runTurn(SessionState state, ai.singlr.session.SessionLimits limits) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(limits, "limits must not be null");

    hookRunner.fire(HookPhase.PRE_MODEL_TURN);

    var visibilityCtx = new ToolVisibilityContext(state.sessionId(), state.currentTurnIndex());
    var visibleTools =
        toolDispatch.registry().visible(visibilityCtx).stream().map(ToolBinding::tool).toList();

    var subscriber = new TurnSubscriber(state);
    try {
      var publisher = model.chatStream(state.historySnapshot(), visibleTools, state.cancellation());
      publisher.subscribe(subscriber);
    } catch (Throwable t) {
      subscriber.onError(t);
    }
    subscriber.awaitDone();

    var streamOutcome = subscriber.toOutcome();
    var toolCalls = subscriber.toolCalls();

    if (!toolCalls.isEmpty()) {
      state.appendMessage(Message.assistant(streamOutcome.assistantContent(), toolCalls));
      dispatchToolCalls(state, toolCalls);
    } else if (streamOutcome.finishReason() != FinishReason.ERROR
        && !streamOutcome.assistantContent().isEmpty()) {
      state.appendMessage(Message.assistant(streamOutcome.assistantContent()));
    }
    state.accumulateUsage(streamOutcome.usage());

    var finalOutcome =
        toolCalls.isEmpty()
            ? streamOutcome
            : new TurnOutcome(
                FinishReason.TOOL_CALLS, streamOutcome.assistantContent(), streamOutcome.usage());

    emit(
        new QueryEvent.TurnEnded(
            state.sessionId(),
            state.currentTurnIndex(),
            clock.instant(),
            mapFinishReasonToStopReason(finalOutcome.finishReason())));

    hookRunner.fire(HookPhase.POST_MODEL_TURN);
    return finalOutcome;
  }

  private void dispatchToolCalls(SessionState state, List<ToolCall> toolCalls) {
    for (var call : toolCalls) {
      emit(
          new QueryEvent.ToolUse(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), call));
      ToolResult result;
      try {
        result = toolDispatch.dispatch(call, state.cancellation());
      } catch (Throwable t) {
        var msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        result = ToolResult.failure("tool dispatch failed: " + msg);
      }
      emit(
          new QueryEvent.ToolResult(
              state.sessionId(), state.currentTurnIndex(), clock.instant(), call, result));
      state.appendMessage(Message.tool(call.id(), call.name(), result.output()));
    }
  }

  private void emit(QueryEvent event) {
    eventSink.accept(event);
    hookRunner.fire(HookPhase.ON_STREAM_EVENT);
  }

  private static ai.singlr.session.StopReason mapFinishReasonToStopReason(FinishReason r) {
    return switch (r) {
      case STOP -> ai.singlr.session.StopReason.END_TURN;
      case TOOL_CALLS -> ai.singlr.session.StopReason.TOOL_USE;
      case LENGTH -> ai.singlr.session.StopReason.MAX_TOKENS;
      case CONTENT_FILTER -> ai.singlr.session.StopReason.REFUSAL;
      case ERROR -> ai.singlr.session.StopReason.ERROR;
    };
  }

  /** Internal subscriber that translates {@link ModelChunk}s into {@link QueryEvent}s. */
  private final class TurnSubscriber implements Flow.Subscriber<ModelChunk> {

    private final SessionState state;
    private final StringBuilder content = new StringBuilder();
    private final List<ToolCall> toolCalls = new CopyOnWriteArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<FinishReason> finishReason =
        new AtomicReference<>(FinishReason.STOP);
    private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.of(0, 0));
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    TurnSubscriber(SessionState state) {
      this.state = state;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ModelChunk chunk) {
      switch (chunk) {
        case ModelChunk.TextDelta(String text) -> handleTextDelta(text);
        case ModelChunk.ThinkingDelta(String text) -> handleThinkingDelta(text);
        case ModelChunk.ToolUseStop(ToolCall call) -> toolCalls.add(call);
        case ModelChunk.MessageStop(String stopReason, Usage u) -> handleMessageStop(stopReason, u);
        case ModelChunk.UsageDelta ignored -> {}
        case ModelChunk.ToolUseStart ignored -> {}
        case ModelChunk.ToolUseDelta ignored -> {}
      }
    }

    private void handleTextDelta(String text) {
      content.append(text);
      emit(new QueryEvent.AssistantText(state.sessionId(), state.currentTurnIndex(), now(), text));
    }

    private void handleThinkingDelta(String text) {
      emit(
          new QueryEvent.AssistantThinking(
              state.sessionId(), state.currentTurnIndex(), now(), text, ""));
    }

    private void handleMessageStop(String stopReason, Usage u) {
      finishReason.set(parseFinishReason(stopReason));
      usage.set(u);
    }

    @Override
    public void onError(Throwable t) {
      error.set(t);
      finishReason.set(FinishReason.ERROR);
      done.countDown();
    }

    @Override
    public void onComplete() {
      done.countDown();
    }

    void awaitDone() {
      try {
        done.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        error.compareAndSet(null, e);
        finishReason.set(FinishReason.ERROR);
      }
    }

    TurnOutcome toOutcome() {
      var err = error.get();
      var assistantContent =
          err != null
              ? (err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage())
              : content.toString();
      return new TurnOutcome(finishReason.get(), assistantContent, usage.get());
    }

    List<ToolCall> toolCalls() {
      return new ArrayList<>(toolCalls);
    }

    private Instant now() {
      return clock.instant();
    }
  }

  private static FinishReason parseFinishReason(String stopReason) {
    try {
      return FinishReason.valueOf(stopReason);
    } catch (IllegalArgumentException ignored) {
      return FinishReason.STOP;
    }
  }
}
