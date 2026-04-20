/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.Locale;

/**
 * Outcome of one autoresearch iteration.
 *
 * <ul>
 *   <li>{@link #KEEP} — the candidate improved on the baseline; keep the new state.
 *   <li>{@link #DISCARD} — the candidate did not improve; revert to the prior state.
 *   <li>{@link #CRASH} — the objective threw or the candidate was malformed; revert.
 * </ul>
 */
public enum ExperimentStatus {
  KEEP,
  DISCARD,
  CRASH;

  /**
   * Wire-format name used in the JSONL log and in tool parameters ({@code "keep"}, {@code
   * "discard"}, {@code "crash"}).
   *
   * @return the lowercase enum name
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parse a wire-format name back into an {@link ExperimentStatus}. Matching is case-insensitive.
   *
   * @param wire the string to parse
   * @return the matching enum value
   * @throws IllegalArgumentException if {@code wire} is null or not a known status
   */
  public static ExperimentStatus fromWire(String wire) {
    if (wire == null) {
      throw new IllegalArgumentException("wire must not be null");
    }
    return switch (wire.toLowerCase(Locale.ROOT)) {
      case "keep" -> KEEP;
      case "discard" -> DISCARD;
      case "crash" -> CRASH;
      default -> throw new IllegalArgumentException("unknown status: " + wire);
    };
  }
}
