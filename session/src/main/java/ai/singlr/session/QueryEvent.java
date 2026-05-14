/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.time.Instant;
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
 * <p>The hierarchy is grown incrementally as the SDK's subsystems land:
 *
 * <ul>
 *   <li>Now: {@link AssistantText}, {@link AssistantThinking}, {@link UserMessageReceived}, {@link
 *       ContextWarning}, {@link ContextEdited}, {@link TurnEnded}, {@link LoopEnded}, {@link
 *       Error}.
 *   <li>Later (with {@code Tool} integration): {@code ToolUse}, {@code ToolResult}, {@code
 *       ToolBlocked}, {@code ToolMutated}.
 *   <li>Later (with {@code Hook} integration): {@code HookFired}.
 * </ul>
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
}
