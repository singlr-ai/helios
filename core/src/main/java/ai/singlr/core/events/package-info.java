/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

/**
 * Unified event stream for live observability of Helios primitive runs.
 *
 * <p>This package defines a single ordered, append-only stream of {@link
 * ai.singlr.core.events.HeliosEvent} values that captures everything a live UI would want to render
 * — from {@code Agent.run}, {@code RlmHarness.run}, {@code CodeActHarness.run}, {@code Team.run},
 * and the autoresearch optimizers. Same event type, same subscription mechanism, regardless of
 * which top-level primitive the user invoked.
 *
 * <p>{@link ai.singlr.core.events.EventSink} is the <em>single</em> observability SPI for Helios.
 * It covers run lifecycle, iteration boundaries, assistant text and thinking, tool calls, span
 * open/close, memory mutations, sub-agent delegation, compaction, and optimizer progress. The
 * provider-level {@link ai.singlr.core.model.StreamEvent} channel remains separate at the provider
 * boundary; the agent loop translates it into {@code AssistantTextDelta} / {@code
 * AssistantThinkingDelta} for sinks.
 *
 * <p>Library consumers tap in by registering an {@link ai.singlr.core.events.EventSink}. Two
 * reference sinks ship in core: {@link ai.singlr.core.events.CollectingEventSink} (accumulates into
 * a list for tests and snapshot UIs) and {@link ai.singlr.core.events.JsonlEventSink} (writes one
 * JSON line per event to a file for session replay).
 */
package ai.singlr.core.events;
