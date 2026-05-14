/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Per-turn execution context bound on the agent loop's virtual thread via JEP 506 {@link
 * ScopedValue}.
 *
 * <p>Carries the {@code sessionId}, the current {@code turnIndex}, and the session's {@link
 * CancellationToken} so tool code, hooks, and stream subscribers can read them without dragging a
 * context object through every signature. {@code SessionScope} is immutable — each turn rebinds a
 * fresh scope via {@link #withNextTurn()}.
 *
 * <p>Audit and other future per-turn collaborators are added as fields here when their owning
 * commits land; the binding API does not change.
 *
 * <h2>Thread-safety</h2>
 *
 * Instances are immutable and safe to share. {@link ScopedValue} bindings are inherited by virtual
 * threads forked from a bound thread per the standard JEP 506 semantics; bindings do not leak
 * across {@link ScopedValue#where} boundaries.
 *
 * <p>Replaces {@code ai.singlr.core.agent.Agent.PARENT_SPAN} from 1.5. The trace-parent role moves
 * to the loop's span machinery (landed in a later commit); here we carry only the session-level
 * facts.
 */
public final class SessionScope {

  private static final ScopedValue<SessionScope> CURRENT = ScopedValue.newInstance();

  private final String sessionId;
  private final long turnIndex;
  private final CancellationToken cancellation;

  /**
   * Canonical constructor.
   *
   * @param sessionId a stable, non-blank identifier for the session
   * @param turnIndex the current turn index; non-negative
   * @param cancellation the session's cancellation token; non-null
   * @throws NullPointerException if {@code sessionId} or {@code cancellation} is null
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code turnIndex} is negative
   */
  public SessionScope(String sessionId, long turnIndex, CancellationToken cancellation) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (turnIndex < 0) {
      throw new IllegalArgumentException("turnIndex must be non-negative, got " + turnIndex);
    }
    this.sessionId = sessionId;
    this.turnIndex = turnIndex;
    this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
  }

  /**
   * The session's stable identifier.
   *
   * @return non-blank session id
   */
  public String sessionId() {
    return sessionId;
  }

  /**
   * The current turn index (0-based; incremented at every iteration boundary).
   *
   * @return non-negative turn index
   */
  public long turnIndex() {
    return turnIndex;
  }

  /**
   * The session's cancellation token.
   *
   * @return non-null token
   */
  public CancellationToken cancellation() {
    return cancellation;
  }

  /**
   * Build a fresh scope identical to this one but with {@code turnIndex + 1}.
   *
   * @return a new scope ready to bind for the next turn
   */
  public SessionScope withNextTurn() {
    return new SessionScope(sessionId, turnIndex + 1, cancellation);
  }

  /**
   * The scope bound on the current thread.
   *
   * @return the bound scope
   * @throws IllegalStateException if no scope is bound on this thread
   */
  public static SessionScope current() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException("No SessionScope is bound on this thread");
    }
    return CURRENT.get();
  }

  /**
   * The scope bound on the current thread, or empty if unbound. Use when code may run inside or
   * outside a session loop.
   *
   * @return the bound scope, or {@link Optional#empty()} if unbound
   */
  public static Optional<SessionScope> currentOptional() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  /**
   * Run {@code body} with {@code scope} bound as the current {@code SessionScope}. The binding is
   * released when {@code body} returns or throws.
   *
   * @param scope the scope to bind; non-null
   * @param body the work to perform; non-null
   * @param <R> the return type of {@code body}
   * @return whatever {@code body} returns
   * @throws NullPointerException if {@code scope} or {@code body} is null
   * @throws Exception whatever {@code body} throws
   */
  public static <R> R callWith(SessionScope scope, Callable<R> body) throws Exception {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(body, "body must not be null");
    return ScopedValue.where(CURRENT, scope).call(body::call);
  }

  /**
   * Run {@code body} with {@code scope} bound as the current {@code SessionScope}. Convenience for
   * void-returning work.
   *
   * @param scope the scope to bind; non-null
   * @param body the work to perform; non-null
   * @throws NullPointerException if {@code scope} or {@code body} is null
   */
  public static void runWith(SessionScope scope, Runnable body) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(body, "body must not be null");
    ScopedValue.where(CURRENT, scope).run(body);
  }
}
