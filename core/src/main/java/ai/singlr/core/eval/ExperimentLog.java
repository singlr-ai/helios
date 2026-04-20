/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.List;

/**
 * Durable append-only log of autoresearch iterations.
 *
 * <p>An experiment log has two responsibilities: record each iteration's {@link ExperimentEntry},
 * and expose the full history so a resuming agent can reconstruct what was tried after a context
 * reset. Implementations must tolerate concurrent appends from a single process; multi-process
 * concurrency is out of scope.
 *
 * <p>The log is organized into <b>segments</b>. A segment is a window of iterations sharing the
 * same optimization baseline. Calling {@link #newSegment()} starts a new segment; prior segments
 * remain visible but do not participate in confidence scoring for new entries.
 */
public interface ExperimentLog extends AutoCloseable {

  /**
   * Append an entry. Thread-safe; entries must be persisted before the call returns.
   *
   * @param entry the entry to append
   */
  void append(ExperimentEntry entry);

  /**
   * Return all entries across all segments, in append order.
   *
   * @return an immutable list of all entries
   */
  List<ExperimentEntry> entries();

  /**
   * Return the entries in the given segment, in append order.
   *
   * @param segmentId segment identifier
   * @return an immutable list of entries in that segment
   */
  List<ExperimentEntry> segment(int segmentId);

  /**
   * Return the current segment identifier. New entries default to this segment.
   *
   * @return the current segment
   */
  int currentSegment();

  /**
   * Start a new segment. Subsequent {@link #append(ExperimentEntry)} calls should use the new
   * segment id (visible via {@link #currentSegment()}) unless the caller overrides it.
   *
   * @return the new segment identifier
   */
  int newSegment();

  @Override
  void close();
}
