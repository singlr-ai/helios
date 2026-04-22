/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe {@link TraceListener} that accumulates every fired trace into an in-memory list.
 *
 * <p>With nested-trace propagation (see {@code Agent.PARENT_SPAN}), a single {@code Team.run(...)}
 * produces one unified trace containing every worker's model and tool spans as children of the
 * corresponding {@code tool.<worker>} delegation span — so this collector will usually hold one
 * entry per top-level agent run. Standalone worker runs (called outside a team) each emit their own
 * trace and appear as separate entries.
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * var collector = new CollectingTraceListener();
 * var agent = new Agent(AgentConfig.newBuilder()
 *     .withModel(model)
 *     .withTraceListener(collector)
 *     .build());
 * agent.run("hello");
 * List<Trace> traces = collector.traces();
 * }</pre>
 */
public final class CollectingTraceListener implements TraceListener {

  private final List<Trace> traces = new CopyOnWriteArrayList<>();

  @Override
  public void onTrace(Trace trace) {
    traces.add(trace);
  }

  /**
   * Snapshot of all traces captured so far, in firing order. Returned list is immutable; further
   * traces do not appear in it.
   *
   * @return an immutable snapshot of captured traces
   */
  public List<Trace> traces() {
    return List.copyOf(traces);
  }

  /**
   * Most recently captured trace, or {@code null} if none yet.
   *
   * @return the latest trace, or {@code null}
   */
  public Trace latest() {
    return traces.isEmpty() ? null : traces.get(traces.size() - 1);
  }

  /** Remove all captured traces. After this call {@link #traces()} returns an empty list. */
  public void clear() {
    traces.clear();
  }

  /**
   * Number of traces captured so far.
   *
   * @return the trace count
   */
  public int size() {
    return traces.size();
  }
}
