/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Response.Usage;
import java.util.Map;
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
 * authoritative final tally for the turn. {@code metadata} carries provider-round-trip data (Gemini
 * thought signatures, Anthropic citation pointers, …) lifted off the {@code MessageStop} chunk; the
 * agent loop stores it on the assistant message so it survives into the next turn's follow-up
 * request.
 *
 * @param finishReason parsed finish reason from the {@code MessageStop} chunk; non-null
 * @param assistantContent assembled assistant text; non-null, possibly empty
 * @param usage final usage at message end; non-null
 * @param metadata provider-specific assistant-message metadata; non-null, defensively copied, may
 *     be empty
 */
public record TurnOutcome(
    FinishReason finishReason, String assistantContent, Usage usage, Map<String, String> metadata) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   */
  public TurnOutcome {
    Objects.requireNonNull(finishReason, "finishReason must not be null");
    Objects.requireNonNull(assistantContent, "assistantContent must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    metadata = Map.copyOf(metadata);
  }

  /** Back-compat convenience for callers that don't carry provider metadata. */
  public TurnOutcome(FinishReason finishReason, String assistantContent, Usage usage) {
    this(finishReason, assistantContent, usage, Map.of());
  }
}
