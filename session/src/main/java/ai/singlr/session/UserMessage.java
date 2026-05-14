/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.util.Objects;

/**
 * A message from the user to the agent session.
 *
 * <p>Construct via {@link #text(String)} for the common plain-text case, or directly via the
 * canonical constructor.
 *
 * <p>{@code UserMessage} carries no timestamp or sender identity — the agent loop captures those
 * via {@code QueryEvent.UserMessageReceived} when the message is observed.
 *
 * @param text the message text; non-null and non-blank
 */
public record UserMessage(String text) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code text} is null
   * @throws IllegalArgumentException if {@code text} is blank (empty or whitespace-only)
   */
  public UserMessage {
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }

  /**
   * Convenience factory equivalent to {@code new UserMessage(text)}.
   *
   * @param text the message text
   * @return a new {@code UserMessage}
   */
  public static UserMessage text(String text) {
    return new UserMessage(text);
  }
}
