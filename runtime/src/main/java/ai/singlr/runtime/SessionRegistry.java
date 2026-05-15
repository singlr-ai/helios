/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import ai.singlr.session.AgentSession;
import ai.singlr.session.SessionOptions;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-memory registry of live sessions. The HTTP service holds one per process; route handlers
 * lookup sessions by id to dispatch send / interrupt / events / close.
 *
 * <p>Sessions are created via {@link #create(SessionOptions)} — a {@link Function} factory wired at
 * construction (typically {@code AgentSession::create}) builds the impl. The factory is injectable
 * so tests can substitute a stub session.
 *
 * <p>Sessions remain in the registry until {@link #close(String)} is called, even after they reach
 * a terminal {@link ai.singlr.session.ResultMessage ResultMessage} — keeping them around lets late
 * SSE subscribers fetch the final {@code LoopEnded} event after termination, and lets the {@code
 * DELETE /sessions/{id}} route be the explicit cleanup boundary.
 *
 * <h2>Thread-safety</h2>
 *
 * Thread-safe. All routes share one registry; concurrent create / get / close are common. Backed by
 * {@link ConcurrentHashMap}; {@link #create(SessionOptions)} rejects duplicate ids.
 */
public final class SessionRegistry {

  private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();
  private final Function<SessionOptions, AgentSession> factory;

  /**
   * Registry that constructs sessions via {@link AgentSession#create(SessionOptions)}.
   *
   * @return a fresh registry
   */
  public static SessionRegistry inMemory() {
    return new SessionRegistry(AgentSession::create);
  }

  /**
   * Registry that constructs sessions via a custom factory.
   *
   * @param factory non-null function mapping options to a fresh session
   * @return a fresh registry
   * @throws NullPointerException if {@code factory} is null
   */
  public static SessionRegistry withFactory(Function<SessionOptions, AgentSession> factory) {
    return new SessionRegistry(factory);
  }

  private SessionRegistry(Function<SessionOptions, AgentSession> factory) {
    this.factory = Objects.requireNonNull(factory, "factory must not be null");
  }

  /**
   * Create a new session from the given options and register it under its session id.
   *
   * @param options the composition record; non-null
   * @return the freshly-created, unstarted session
   * @throws NullPointerException if {@code options} is null
   * @throws IllegalStateException if a session with the same id is already registered
   */
  public AgentSession create(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    var session = factory.apply(options);
    Objects.requireNonNull(session, "factory returned null session");
    var prev = sessions.putIfAbsent(options.sessionId(), session);
    if (prev != null) {
      session.close();
      throw new IllegalStateException("session id already registered: " + options.sessionId());
    }
    return session;
  }

  /**
   * Look up a registered session by id.
   *
   * @param sessionId non-null id
   * @return the session if present
   * @throws NullPointerException if {@code sessionId} is null
   */
  public Optional<AgentSession> get(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return Optional.ofNullable(sessions.get(sessionId));
  }

  /**
   * Close and unregister the session. If no session is registered under {@code sessionId} this is a
   * no-op.
   *
   * @param sessionId non-null id
   * @return {@code true} if a session was found and closed; {@code false} if no session was
   *     registered
   * @throws NullPointerException if {@code sessionId} is null
   */
  public boolean close(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    var session = sessions.remove(sessionId);
    if (session == null) {
      return false;
    }
    session.close();
    return true;
  }

  /**
   * Snapshot of currently-registered session ids. Stable point-in-time view; mutations after this
   * call are not reflected.
   *
   * @return defensive snapshot
   */
  public Collection<String> sessionIds() {
    return Set.copyOf(sessions.keySet());
  }

  /**
   * Number of registered sessions.
   *
   * @return non-negative count
   */
  public int size() {
    return sessions.size();
  }

  /** Close and unregister every session. Idempotent. */
  public void closeAll() {
    for (var id : Set.copyOf(sessions.keySet())) {
      close(id);
    }
  }
}
