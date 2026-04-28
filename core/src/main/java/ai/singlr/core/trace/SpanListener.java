/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * Receives per-span lifecycle events as they happen during agent execution. Parallel to {@link
 * TraceListener}, which only fires when the whole trace closes.
 *
 * <p>{@code SpanListener} is the hook for live observability: streaming span events into a
 * dashboard, driving a "what's my agent doing right now" UI, kill-switching long trajectories, or
 * recording per-iteration telemetry without waiting for the full trace to drain.
 *
 * <p>{@link #onSpanStart} fires immediately after a {@link SpanBuilder} is created (before any
 * children or attributes exist). {@link #onSpanEnd} fires after the span completes and carries the
 * fully-populated immutable {@link Span} record. Implementations correlate the two via {@link
 * SpanStart#spanId()} and {@link Span#id()}.
 *
 * <p><b>Nested trace mode.</b> When an {@code Agent} runs nested inside a {@code Team}'s leader
 * (i.e. {@code Agent.PARENT_SPAN} is bound), the worker does not create its own {@code
 * TraceBuilder}; its spans are created against the leader's container. Worker {@code SpanListener}s
 * configured on the worker's {@code AgentConfig} do not fire in this mode — the leader's listeners
 * receive every event. This matches the existing nested-trace rule for {@link TraceListener}. To
 * receive worker spans live, attach the listener to the {@code Team} (or to the leader's config).
 *
 * <p><b>Listener contract.</b> Implementations must be cheap and non-blocking — these hooks fire on
 * the agent loop's hot path. Exceptions thrown from a listener are caught and ignored so other
 * listeners still receive the event.
 */
public interface SpanListener {

  /**
   * Called immediately after a span is created, before any work is recorded against it.
   *
   * @param event identity and start time of the new span
   */
  default void onSpanStart(SpanStart event) {}

  /**
   * Called after a span completes (either successfully via {@code end()} or with an error via
   * {@code fail()}). The {@link Span} record is fully populated — attributes, children, duration,
   * error are all final.
   *
   * @param span the completed span
   */
  default void onSpanEnd(Span span) {}
}
