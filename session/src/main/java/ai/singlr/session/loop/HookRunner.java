/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.loop;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fires hooks of one phase in priority order for the agent loop.
 *
 * <p>Phase 1 stub. The {@code Hook} sealed interface and the priority/outcome plumbing land in
 * Phase 2; until then, this class accepts an opaque list of hook references and {@link
 * #fire(HookPhase)} is a no-op that records the call count for observability. The shape is
 * deliberately stable so the loop can already wire {@code HookRunner.fire(...)} calls into its
 * lifecycle and the Phase 2 work replaces the body without touching call sites.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. The internal call counter uses {@link AtomicLong}; {@link #hooks()} returns the
 * caller-supplied list as-is, so its mutation semantics are inherited from the caller.
 */
public final class HookRunner {

  private final List<Object> hooks;
  private final AtomicLong fireCount = new AtomicLong();

  /**
   * Build a hook runner wrapping the given hook list. The list is held by reference; defensively
   * snapshotting is the caller's responsibility (or will be added when the real Hook type lands in
   * Phase 2).
   *
   * @param hooks the hook references; non-null, may be empty
   * @throws NullPointerException if {@code hooks} is null
   */
  public HookRunner(List<Object> hooks) {
    this.hooks = Objects.requireNonNull(hooks, "hooks must not be null");
  }

  /**
   * Convenience constructor for a runner with no hooks.
   *
   * @return a runner with an empty hook list
   */
  public static HookRunner empty() {
    return new HookRunner(List.of());
  }

  /**
   * The wrapped hook references. Returned as-is; callers should not assume immutability.
   *
   * @return the hook list
   */
  public List<Object> hooks() {
    return hooks;
  }

  /**
   * Fire all hooks bound to {@code phase}. Phase 1 stub: increments the per-runner call counter and
   * returns. Phase 2 replaces the body with priority-sorted dispatch through {@code Hook} subtypes
   * appropriate to {@code phase}.
   *
   * @param phase the lifecycle phase whose hooks should fire; non-null
   * @throws NullPointerException if {@code phase} is null
   */
  public void fire(HookPhase phase) {
    Objects.requireNonNull(phase, "phase must not be null");
    fireCount.incrementAndGet();
  }

  /**
   * Total number of {@link #fire(HookPhase)} invocations across this runner's lifetime. Useful for
   * tests and Phase 1 acceptance smoke checks; will continue to be valid after Phase 2 fleshes out
   * the dispatch body.
   *
   * @return non-negative count
   */
  public long fireCount() {
    return fireCount.get();
  }
}
