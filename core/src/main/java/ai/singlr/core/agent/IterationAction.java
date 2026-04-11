/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

/**
 * The decision returned by an {@link IterationHook}. Sealed interface with three variants:
 *
 * <ul>
 *   <li>{@link Allow} — permit the agent to complete. Functionally identical to {@link Stop} but
 *       documents semantic intent ("no objection").
 *   <li>{@link Stop} — force the agent to complete. Functionally identical to {@link Allow} but
 *       documents semantic intent ("I decided this is done").
 *   <li>{@link Inject} — force another iteration with a user-authored guidance message appended to
 *       the conversation.
 * </ul>
 *
 * <p>Trace attributes record which variant was returned so observers can distinguish {@code Allow}
 * from {@code Stop} in post-hoc analysis.
 */
public sealed interface IterationAction {

  /** Permit the agent to complete. */
  static Allow allow() {
    return new Allow();
  }

  /** Force the agent to complete. */
  static Stop stop() {
    return new Stop();
  }

  /** Force another iteration with the given guidance message. */
  static Inject inject(String message) {
    return new Inject(message);
  }

  /** Permit the agent to complete. Semantic sibling of {@link Stop}. */
  record Allow() implements IterationAction {}

  /** Force the agent to complete. Semantic sibling of {@link Allow}. */
  record Stop() implements IterationAction {}

  /**
   * Force another iteration. The {@code message} is appended to the conversation as a {@code USER}
   * role message with metadata {@code helios.injected=iterationHook}.
   *
   * @param message the guidance to inject; must be non-null and non-blank
   */
  record Inject(String message) implements IterationAction {
    public Inject {
      if (message == null || message.isBlank()) {
        throw new IllegalArgumentException("Inject message must be non-blank");
      }
    }
  }
}
