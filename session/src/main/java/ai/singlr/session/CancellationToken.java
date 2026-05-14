/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cooperative cancellation signal for an agent session.
 *
 * <p>One writer (typically the session that creates the token), many readers (the agent loop, tool
 * implementations, model stream subscribers). State is set once; subsequent {@link #cancel(String)}
 * calls are no-ops and the first reason is preserved.
 *
 * <p>Cancellation is cooperative: code performing long-running work is responsible for polling
 * {@link #isCancelled()} or calling {@link #throwIfCancelled()} at safe points. The token itself
 * does not interrupt OS threads, close I/O streams, or unsubscribe {@code Flow.Subscription}s —
 * those side effects are wired by the consumer.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. {@code cancel}, {@code isCancelled}, {@code reason}, and {@code throwIfCancelled}
 * may be called from any thread concurrently; the {@code compareAndSet} on the underlying {@link
 * AtomicReference} guarantees that only the first {@code cancel} that wins the race sets the state.
 */
public final class CancellationToken {

  private final AtomicReference<String> reason = new AtomicReference<>();

  /**
   * Whether {@link #cancel(String)} has been called at least once.
   *
   * @return {@code true} if cancelled
   */
  public boolean isCancelled() {
    return reason.get() != null;
  }

  /**
   * The reason recorded by the first successful {@link #cancel(String)} call.
   *
   * @return the cancellation reason, or {@link Optional#empty()} if not cancelled
   */
  public Optional<String> reason() {
    return Optional.ofNullable(reason.get());
  }

  /**
   * Signal cancellation. The first call with a non-null, non-blank reason wins; subsequent calls
   * are no-ops and the first reason is preserved. The return value lets callers distinguish "I was
   * the cause" from "someone else cancelled first" — useful for audit attribution.
   *
   * @param reason a human-readable reason for the cancellation
   * @return {@code true} if this call transitioned the token to cancelled; {@code false} if it was
   *     already cancelled
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  public boolean cancel(String reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    return this.reason.compareAndSet(null, reason);
  }

  /**
   * Throw if this token has been cancelled. Tools and other cooperative cancellation participants
   * should call this at safe points in their work.
   *
   * @throws CancellationException if cancelled; the exception message is the cancellation reason
   */
  public void throwIfCancelled() {
    var r = reason.get();
    if (r != null) {
      throw new CancellationException(r);
    }
  }
}
