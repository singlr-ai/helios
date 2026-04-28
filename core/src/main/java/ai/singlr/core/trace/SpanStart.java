/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Identity and start time of a newly-created span, dispatched to {@link SpanListener#onSpanStart}.
 *
 * <p>At span-start time the attributes map and children list are empty and the duration is not yet
 * known; that information is delivered later via {@link SpanListener#onSpanEnd} carrying the
 * complete {@link Span} record. Listeners correlate the two events via {@link #spanId()} and {@link
 * Span#id()}.
 *
 * @param spanId unique identifier for this span (matches {@link Span#id()} at end time)
 * @param traceId identifier of the enclosing trace; for nested {@code Team} workers this is the
 *     leader's trace id
 * @param parentSpanId identifier of the parent span, or {@code null} when this span is a top-level
 *     child of the trace
 * @param name descriptive name (e.g. {@code "model.chat"}, {@code "tool.execute_code"})
 * @param kind the kind of work this span represents
 * @param startTime when the span was created
 */
public record SpanStart(
    UUID spanId,
    UUID traceId,
    UUID parentSpanId,
    String name,
    SpanKind kind,
    OffsetDateTime startTime) {}
