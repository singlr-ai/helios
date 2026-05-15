/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.UserMessage;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Priority-sorted hook dispatcher. One instance per session, built from the hook list supplied via
 * {@link ai.singlr.session.SessionOptions.Builder#withHook(Hook)}.
 *
 * <p>Hooks of each phase are sorted by {@link Hook#priority()} (low → high), then by registration
 * order for ties. For non-observe phases, firing returns the first non-{@link HookOutcome.Continue}
 * outcome it sees — see spec §9.4. Stream-event hooks all fire (observe-only).
 *
 * <p>If a hook throws, the orchestrator treats the outcome as {@link HookOutcome.Continue} and logs
 * at {@code WARNING} — see spec §9.4. Hook misbehavior cannot abort the session.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable after construction. Safe to share across threads.
 */
public final class HookRegistry {

  private static final Logger LOGGER = Logger.getLogger(HookRegistry.class.getName());

  private final List<PreToolUseHook> preToolUse;
  private final List<PostToolUseHook> postToolUse;
  private final List<PreModelTurnHook> preModelTurn;
  private final List<PostModelTurnHook> postModelTurn;
  private final List<PreStopHook> preStop;
  private final List<OnUserMessageHook> onUserMessage;
  private final List<OnStreamEventHook> onStreamEvent;

  /**
   * Build a registry from the given hooks. Hooks are partitioned by phase via their sealed permits
   * and sorted by priority.
   *
   * @param hooks the hooks; non-null but may be empty
   * @throws NullPointerException if {@code hooks} is null or contains null elements
   */
  public HookRegistry(List<? extends Hook> hooks) {
    Objects.requireNonNull(hooks, "hooks must not be null");
    for (var h : hooks) {
      Objects.requireNonNull(h, "hooks must not contain null");
    }
    this.preToolUse = sortByPriority(hooks, PreToolUseHook.class);
    this.postToolUse = sortByPriority(hooks, PostToolUseHook.class);
    this.preModelTurn = sortByPriority(hooks, PreModelTurnHook.class);
    this.postModelTurn = sortByPriority(hooks, PostModelTurnHook.class);
    this.preStop = sortByPriority(hooks, PreStopHook.class);
    this.onUserMessage = sortByPriority(hooks, OnUserMessageHook.class);
    this.onStreamEvent = sortByPriority(hooks, OnStreamEventHook.class);
  }

  /**
   * Empty registry — no hooks of any phase.
   *
   * @return a fresh empty registry
   */
  public static HookRegistry empty() {
    return new HookRegistry(List.of());
  }

  private static <H extends Hook> List<H> sortByPriority(
      List<? extends Hook> hooks, Class<H> phase) {
    return hooks.stream()
        .filter(phase::isInstance)
        .map(phase::cast)
        .sorted(Comparator.comparingInt(Hook::priority))
        .toList();
  }

  /**
   * Number of registered hooks of the given phase. Useful for tests and observability.
   *
   * @param phase the phase class
   * @return non-negative count
   */
  public int countOf(Class<? extends Hook> phase) {
    return switch (phase.getSimpleName()) {
      case "PreToolUseHook" -> preToolUse.size();
      case "PostToolUseHook" -> postToolUse.size();
      case "PreModelTurnHook" -> preModelTurn.size();
      case "PostModelTurnHook" -> postModelTurn.size();
      case "PreStopHook" -> preStop.size();
      case "OnUserMessageHook" -> onUserMessage.size();
      case "OnStreamEventHook" -> onStreamEvent.size();
      default -> 0;
    };
  }

  /**
   * Fire every registered {@link PreToolUseHook} until one returns a non-{@link
   * HookOutcome.Continue} outcome. Returns {@link HookOutcome.Continue} if no hook decides
   * anything.
   *
   * @param call the impending tool call; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome firePreToolUse(ToolCall call, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : preToolUse) {
      var outcome = safeFire(hook, () -> hook.beforeTool(call, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link PostToolUseHook}.
   *
   * @param call the call that ran; non-null
   * @param result the tool result; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome firePostToolUse(ToolCall call, ToolResult result, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : postToolUse) {
      var outcome = safeFire(hook, () -> hook.afterTool(call, result, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link PreModelTurnHook}.
   *
   * @param history the history about to be sent; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome firePreModelTurn(List<Message> history, HookContext ctx) {
    Objects.requireNonNull(history, "history must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : preModelTurn) {
      var outcome = safeFire(hook, () -> hook.beforeModelTurn(history, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link PostModelTurnHook}.
   *
   * @param response the model response; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome firePostModelTurn(Response<?> response, HookContext ctx) {
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : postModelTurn) {
      var outcome = safeFire(hook, () -> hook.afterModelTurn(response, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link PreStopHook}.
   *
   * @param stopResponse the response the loop is about to declare terminal; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome firePreStop(Response<?> stopResponse, HookContext ctx) {
    Objects.requireNonNull(stopResponse, "stopResponse must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : preStop) {
      var outcome = safeFire(hook, () -> hook.beforeStop(stopResponse, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link OnUserMessageHook}.
   *
   * @param msg the user message; non-null
   * @param ctx the per-invocation context; non-null
   * @return the first decisive outcome, or {@link HookOutcome.Continue}
   */
  public HookOutcome fireOnUserMessage(UserMessage msg, HookContext ctx) {
    Objects.requireNonNull(msg, "msg must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : onUserMessage) {
      var outcome = safeFire(hook, () -> hook.onUserMessage(msg, ctx));
      if (!(outcome instanceof HookOutcome.Continue)) {
        return outcome;
      }
    }
    return HookOutcome.cont();
  }

  /**
   * Fire every registered {@link OnStreamEventHook}. Observe-only: any hook misbehavior is logged
   * but does not propagate.
   *
   * @param event the event just emitted; non-null
   * @param ctx the per-invocation context; non-null
   */
  public void fireOnStreamEvent(QueryEvent event, HookContext ctx) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(ctx, "ctx must not be null");
    for (var hook : onStreamEvent) {
      try {
        hook.onEvent(event, ctx);
      } catch (RuntimeException ex) {
        LOGGER.log(Level.WARNING, "hook " + hook.name() + " threw on stream event", ex);
      }
    }
  }

  private static HookOutcome safeFire(Hook hook, java.util.function.Supplier<HookOutcome> body) {
    try {
      var outcome = body.get();
      if (outcome == null) {
        LOGGER.warning("hook " + hook.name() + " returned null outcome; treating as Continue");
        return HookOutcome.cont();
      }
      return outcome;
    } catch (RuntimeException ex) {
      LOGGER.log(Level.WARNING, "hook " + hook.name() + " threw; treating as Continue", ex);
      return HookOutcome.cont();
    }
  }
}
