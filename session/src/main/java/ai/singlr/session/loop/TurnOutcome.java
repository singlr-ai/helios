/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Response.Usage;
import java.util.Objects;

/**
 * Outcome of one model turn, produced by {@link TurnRunner#runTurn(SessionState,
 * ai.singlr.session.SessionLimits)} and consumed by {@link StopClassifier#classify(SessionState,
 * ai.singlr.session.SessionLimits, FinishReason, String, boolean)}.
 *
 * <p>{@code assistantContent} is the fully-assembled assistant text accumulated from every {@link
 * ai.singlr.core.model.ModelChunk.TextDelta TextDelta} chunk during the turn — never null, possibly
 * empty (a tool-call-only turn produces empty content). {@code usage} is the {@link
 * ai.singlr.core.model.ModelChunk.MessageStop MessageStop} usage from the same turn — the
 * authoritative final tally for the turn.
 *
 * @param finishReason parsed finish reason from the {@code MessageStop} chunk; non-null
 * @param assistantContent assembled assistant text; non-null, possibly empty
 * @param usage final usage at message end; non-null
 */
public record TurnOutcome(FinishReason finishReason, String assistantContent, Usage usage) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   */
  public TurnOutcome {
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    Objects.requireNonNull(assistantContent, "assistantContent must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
  }
}
