/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.model.ToolCall;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Streamed event from an agent session.
 *
 * <p>Sealed; pattern-matched by subscribers (HTTP SSE, audit sink, trace listeners, custom UI) to
 * surface assistant text, tool activity, context-editing decisions, and lifecycle transitions.
 * Subscribers branch on the subtype via {@code switch}; they never parse strings.
 *
 * <p>Every subtype carries the same three common fields ({@code sessionId}, {@code turnIndex},
 * {@code timestamp}) plus subtype-specific detail. Common-field validation lives in {@link
 * #validateCommon(String, long, Instant)} so the record bodies stay tight.
 *
 * <p>The 13 subtypes cover every observable lifecycle event the agent loop emits — assistant
 * output, user input, context lifecycle, tool dispatch, hook activity, and terminal results.
 *
 * <p>Adding a subtype is a breaking change for {@code switch} consumers that lack a {@code default}
 * branch — by design, so the compiler flags consumers that need updating.
 */
public sealed interface QueryEvent
    permits QueryEvent.AssistantText,
        QueryEvent.AssistantThinking,
        QueryEvent.UserMessageReceived,
        QueryEvent.ContextWarning,
        QueryEvent.ContextEdited,
        QueryEvent.ToolUse,
        QueryEvent.ToolResult,
        QueryEvent.ToolBlocked,
        QueryEvent.ToolMutated,
        QueryEvent.HookFired,
        QueryEvent.TurnEnded,
        QueryEvent.LoopEnded,
        QueryEvent.Error {

  /**
   * The stable identifier of the session that produced this event.
   *
   * @return non-blank session id
   */
  String sessionId();

  /**
   * The turn index in which this event occurred.
   *
   * @return non-negative turn index
   */
  long turnIndex();

  /**
   * The wall-clock instant at which this event was produced.
   *
   * @return non-null timestamp
   */
  Instant timestamp();

  /**
   * Validate the three fields every subtype shares.
   *
   * @throws NullPointerException if {@code sessionId} or {@code timestamp} is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code turnIndex} is negative
   */
  static void validateCommon(String sessionId, long turnIndex, Instant timestamp) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must be non-negative, got " + turnIndex);
    }
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  /**
   * Assistant produced a token (or token batch) of plain text.
   *
   * <p>{@code text} may be empty — providers occasionally emit empty deltas during a stream.
   * Subscribers concatenate the {@code text} across the turn to assemble the full assistant
   * message.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the text delta; non-null, may be empty
   */
  record AssistantText(String sessionId, long turnIndex, Instant timestamp, String text)
      implements QueryEvent {

    public AssistantText {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * Assistant produced a reasoning / thinking delta (extended-thinking models only).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param text the thinking text delta; non-null, may be empty
   * @param signature provider-supplied verification signature for the thinking block; non-null, may
   *     be empty
   */
  record AssistantThinking(
      String sessionId, long turnIndex, Instant timestamp, String text, String signature)
      implements QueryEvent {

    public AssistantThinking {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(text, "text must not be null");
      Objects.requireNonNull(signature, "signature must not be null");
    }
  }

  /**
   * A user message arrived (via {@code send(...)} or the post-interrupt synthetic message).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the message will be processed
   * @param timestamp the event timestamp
   * @param message the user's message
   */
  record UserMessageReceived(
      String sessionId, long turnIndex, Instant timestamp, UserMessage message)
      implements QueryEvent {

    public UserMessageReceived {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * Context-window usage crossed a watermark; compaction is imminent or recommended.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param usagePct fraction of the context window consumed; non-negative
   */
  record ContextWarning(String sessionId, long turnIndex, Instant timestamp, double usagePct)
      implements QueryEvent {

    public ContextWarning {
      validateCommon(sessionId, turnIndex, timestamp);
      if (usagePct < 0.0 || Double.isNaN(usagePct)) {
        throw new IllegalArgumentException(
            "usagePct must be non-negative and finite, got " + usagePct);
      }
    }
  }

  /**
   * Context compaction completed. Tokens went down (typically); blocks were dropped, summarised, or
   * pruned.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index
   * @param timestamp the event timestamp
   * @param removedBlocks count of messages removed or collapsed; non-negative
   * @param tokensBefore estimated tokens before compaction; non-negative
   * @param tokensAfter estimated tokens after compaction; non-negative
   */
  record ContextEdited(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      int removedBlocks,
      long tokensBefore,
      long tokensAfter)
      implements QueryEvent {

    public ContextEdited {
      validateCommon(sessionId, turnIndex, timestamp);
      if (removedBlocks < 0) {
        throw new IllegalArgumentException(
            "removedBlocks must be non-negative, got " + removedBlocks);
      }
      if (tokensBefore < 0) {
        throw new IllegalArgumentException(
            "tokensBefore must be non-negative, got " + tokensBefore);
      }
      if (tokensAfter < 0) {
        throw new IllegalArgumentException("tokensAfter must be non-negative, got " + tokensAfter);
      }
    }
  }

  /**
   * A turn ended. The {@code reason} carries why; the loop may continue (tool use) or stop
   * (end-of-turn followed by a {@code LoopEnded}).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index that ended
   * @param timestamp the event timestamp
   * @param reason why this turn ended
   */
  record TurnEnded(String sessionId, long turnIndex, Instant timestamp, StopReason reason)
      implements QueryEvent {

    public TurnEnded {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  /**
   * The session terminated. Fired exactly once per session, after all other events.
   *
   * @param sessionId the session id
   * @param turnIndex the final turn index
   * @param timestamp the event timestamp
   * @param result the terminal result message
   */
  record LoopEnded(String sessionId, long turnIndex, Instant timestamp, ResultMessage result)
      implements QueryEvent {

    public LoopEnded {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * An error occurred during the session. May or may not be terminal — terminal errors are also
   * surfaced via a subsequent {@link LoopEnded} with an error-shaped {@link ResultMessage}.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the error occurred
   * @param timestamp the event timestamp
   * @param error the serialized error
   */
  record Error(String sessionId, long turnIndex, Instant timestamp, SerializedError error)
      implements QueryEvent {

    public Error {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(error, "error must not be null");
    }
  }

  /**
   * A tool call was dispatched. Fires once per call, before the tool executes; subscribers learn
   * which tool the model invoked and with which arguments.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the call was dispatched
   * @param timestamp the event timestamp
   * @param call the assembled tool call; non-null
   */
  record ToolUse(String sessionId, long turnIndex, Instant timestamp, ToolCall call)
      implements QueryEvent {

    public ToolUse {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /**
   * A tool call completed. Carries the originating {@link ToolCall} so subscribers can correlate
   * dispatch and result without joining across events, plus the {@link
   * ai.singlr.core.tool.ToolResult ToolResult} the tool produced (success or failure).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the call ran
   * @param timestamp the event timestamp
   * @param call the originating call; non-null
   * @param result the tool result; non-null
   */
  record ToolResult(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      ai.singlr.core.tool.ToolResult result)
      implements QueryEvent {

    public ToolResult {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * A {@link ai.singlr.session.hooks.PreToolUseHook PreToolUseHook} blocked a tool call. The tool
   * was not dispatched; the loop substitutes a synthetic tool result describing the block (so the
   * model sees its action refused with a reason).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the block fired
   * @param timestamp the event timestamp
   * @param call the call that was blocked; non-null
   * @param hookName the name of the hook that blocked the call; non-null and non-blank
   * @param reason the reason the hook supplied; non-null and non-blank
   */
  record ToolBlocked(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      String reason)
      implements QueryEvent {

    public ToolBlocked {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (hookName.isBlank()) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(reason, "reason must not be null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /**
   * A {@link ai.singlr.session.hooks.PreToolUseHook PreToolUseHook} mutated a tool call's
   * arguments. The loop dispatches the tool with {@code inputAfter} instead of {@code inputBefore}.
   * Both maps are surfaced for auditability.
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the mutation fired
   * @param timestamp the event timestamp
   * @param call the original call; non-null
   * @param hookName the name of the hook that mutated the input; non-null and non-blank
   * @param inputBefore the original arguments; non-null (defensively copied)
   * @param inputAfter the replacement arguments; non-null (defensively copied)
   */
  record ToolMutated(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      ToolCall call,
      String hookName,
      Map<String, Object> inputBefore,
      Map<String, Object> inputAfter)
      implements QueryEvent {

    public ToolMutated {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (hookName.isBlank()) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(inputBefore, "inputBefore must not be null");
      Objects.requireNonNull(inputAfter, "inputAfter must not be null");
      inputBefore = Map.copyOf(inputBefore);
      inputAfter = Map.copyOf(inputAfter);
    }
  }

  /**
   * A hook fired and produced a non-{@link ai.singlr.session.hooks.HookOutcome.Continue Continue}
   * outcome. Subscribers can drive UI/audit off this event without polling the registry.
   *
   * <p>Continue outcomes are suppressed by design — surfacing every no-op would drown the stream.
   * Observe-only {@link ai.singlr.session.hooks.OnStreamEventHook OnStreamEventHook} firings are
   * also suppressed (the events they observe are already on the stream).
   *
   * @param sessionId the session id
   * @param turnIndex the turn index in which the hook fired
   * @param timestamp the event timestamp
   * @param hookName the hook's name; non-null and non-blank
   * @param phase the lifecycle phase the hook was bound to (e.g. {@code "PreToolUseHook"});
   *     non-null and non-blank
   * @param outcomeKind the simple class name of the {@link ai.singlr.session.hooks.HookOutcome
   *     HookOutcome} subtype returned (e.g. {@code "Block"}, {@code "Inject"}); non-null and
   *     non-blank
   */
  record HookFired(
      String sessionId,
      long turnIndex,
      Instant timestamp,
      String hookName,
      String phase,
      String outcomeKind)
      implements QueryEvent {

    public HookFired {
      validateCommon(sessionId, turnIndex, timestamp);
      Objects.requireNonNull(hookName, "hookName must not be null");
      if (hookName.isBlank()) {
        throw new IllegalArgumentException("hookName must not be blank");
      }
      Objects.requireNonNull(phase, "phase must not be null");
      if (phase.isBlank()) {
        throw new IllegalArgumentException("phase must not be blank");
      }
      Objects.requireNonNull(outcomeKind, "outcomeKind must not be null");
      if (outcomeKind.isBlank()) {
        throw new IllegalArgumentException("outcomeKind must not be blank");
      }
    }
  }
}
