/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link ExperimentLog} suitable for tests and short-lived sessions. Entries live only
 * for the lifetime of the enclosing process.
 */
public final class InMemoryExperimentLog implements ExperimentLog {

  private final CopyOnWriteArrayList<ExperimentEntry> entries = new CopyOnWriteArrayList<>();
  private final AtomicInteger segment = new AtomicInteger(0);

  @Override
  public void append(ExperimentEntry entry) {
    entries.add(entry);
  }

  @Override
  public List<ExperimentEntry> entries() {
    return List.copyOf(entries);
  }

  @Override
  public List<ExperimentEntry> segment(int segmentId) {
    return entries.stream().filter(e -> e.segment() == segmentId).toList();
  }

  @Override
  public int currentSegment() {
    return segment.get();
  }

  @Override
  public int newSegment() {
    return segment.incrementAndGet();
  }

  @Override
  public void close() {}
}
