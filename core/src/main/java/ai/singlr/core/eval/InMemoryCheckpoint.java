/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory {@link Checkpoint} backed by an {@link AtomicReference}. Suitable for reference-type
 * candidates — strings, immutable records, config objects.
 *
 * <p>{@link #snapshot()} returns the current reference directly; {@link #restore} replaces it. This
 * is thread-safe for the reference itself, but the pointed-at value must be immutable or treated as
 * read-only to avoid surprises.
 *
 * @param <C> the candidate type; should be immutable
 */
public final class InMemoryCheckpoint<C> implements Checkpoint<C> {

  private final AtomicReference<C> current;

  /**
   * Create a checkpoint initialized with the given value.
   *
   * @param initial the initial candidate value
   */
  public InMemoryCheckpoint(C initial) {
    this.current = new AtomicReference<>(initial);
  }

  /**
   * Replace the current candidate with {@code next}. Called by the loop when proposing a new
   * candidate before the objective runs.
   *
   * @param next the new candidate
   */
  public void set(C next) {
    current.set(next);
  }

  /**
   * Return the current candidate without capturing a snapshot.
   *
   * @return the current candidate
   */
  public C current() {
    return current.get();
  }

  @Override
  public C snapshot() {
    return current.get();
  }

  @Override
  public void restore(C snapshot) {
    current.set(snapshot);
  }
}
