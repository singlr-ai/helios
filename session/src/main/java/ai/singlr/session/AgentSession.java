/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.schema.OutputSchema;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * A live, streamable, steerable agent session.
 *
 * <p>The session loop runs on a virtual thread that begins on the first {@link #send(UserMessage)}
 * call. Subscribe to {@link #events()} BEFORE the first {@code send} so the initial chunks reach
 * the subscriber — late subscribers attach to a publisher that has already advanced.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@code AgentSession session = ... ;} construct via implementation factory.
 *   <li>{@code session.events().subscribe(subscriber);} attach observers.
 *   <li>{@code session.send(...);} steer the session — first call starts the loop.
 *   <li>{@code session.interrupt(...);} optional mid-run steering.
 *   <li>{@code ResultMessage r = session.result().get();} await terminal.
 *   <li>{@code session.close();} release resources (idempotent; safe after natural termination).
 * </ol>
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations must be safe for the standard "many producers, one loop" pattern: HTTP/UI threads
 * call {@code send} / {@code interrupt} / {@code close} concurrently; the agent loop thread reads
 * the queue and writes to the publisher; subscribers observe via {@code events()}.
 */
public interface AgentSession extends AutoCloseable {

  /**
   * Build a new session from the given options.
   *
   * @param options the composition record; non-null
   * @return a fresh, unstarted session
   * @throws NullPointerException if {@code options} is null
   */
  static AgentSession create(SessionOptions options) {
    Objects.requireNonNull(options, "options must not be null");
    return new AgentSessionImpl(options);
  }

  /**
   * Queue a user message for the agent loop to consume at the next iteration boundary. The first
   * call also starts the loop on a virtual thread.
   *
   * @param message the message; non-null
   * @throws NullPointerException if {@code message} is null
   * @throws IllegalStateException if the session is already closed, or if the steering queue is
   *     full
   */
  void send(UserMessage message);

  /**
   * Convenience for {@code send(UserMessage.text(text))}.
   *
   * @param text non-null, non-blank text
   */
  default void send(String text) {
    send(UserMessage.text(text));
  }

  /**
   * Steer the session mid-run by queueing a synthetic user message. The session continues; this is
   * not a session-ending action.
   *
   * @param reason a human-readable description; non-null, non-blank
   * @throws NullPointerException if {@code reason} is null
   * @throws IllegalArgumentException if {@code reason} is blank
   * @throws IllegalStateException if the session is closed
   */
  void interrupt(String reason);

  /**
   * Stream of session events. Subscribe BEFORE the first {@link #send(UserMessage)} to observe
   * every chunk. Implementations buffer per-subscriber so a slow subscriber back-pressures the
   * agent loop rather than dropping events.
   *
   * @return a single-publisher to which any number of subscribers may attach
   */
  Flow.Publisher<QueryEvent> events();

  /**
   * Future that completes when the session reaches a terminal {@link ResultMessage}. Completes
   * normally with the terminal value; exceptional completion indicates a session bug, not an
   * agent-loop terminal (those are returned via the value, not the exception).
   *
   * @return the terminal-result future
   */
  CompletableFuture<ResultMessage> result();

  /**
   * The stable session identifier.
   *
   * @return non-blank id
   */
  String sessionId();

  /**
   * The agent loop's current turn index. Reads the live state; subject to change between calls.
   *
   * @return the turn index (0-based; 0 if the loop has not yet started)
   */
  long currentTurnIndex();

  /**
   * Release session resources. Cancels the agent loop, closes the event publisher, and releases the
   * underlying virtual thread. Idempotent — safe to call multiple times, and safe to call after
   * natural termination.
   */
  @Override
  void close();

  /**
   * Blocking convenience: send the message, then await the terminal {@link ResultMessage}.
   * Subscribers — if any — observe the stream in the usual way.
   *
   * @param message the message; non-null
   * @return the terminal result
   * @throws NullPointerException if {@code message} is null
   */
  default ResultMessage runBlocking(UserMessage message) {
    send(message);
    return result().join();
  }

  /**
   * Typed blocking convenience: send the message, await termination, parse the final assistant
   * message against {@code schema}, return the typed result. Phase 2 wires the {@link OutputSchema}
   * parser through the loop's structured-output pathway; Phase 1 sessions are text-only.
   *
   * @param message the message; non-null
   * @param schema the output schema; non-null
   * @param <T> the parsed output type
   * @return the parsed final assistant message
   * @throws NullPointerException if {@code message} or {@code schema} is null
   * @throws UnsupportedOperationException always — typed {@code runBlocking} lands in Phase 2
   */
  default <T> T runBlocking(UserMessage message, OutputSchema<T> schema) {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    throw new UnsupportedOperationException(
        "Typed runBlocking lands in Phase 2 once OutputSchema parsing is wired through the "
            + "session loop. Phase 1 sessions are text-only.");
  }
}
