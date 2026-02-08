/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/**
 * Controls the level of reasoning/thinking trace included in model responses.
 *
 * <p>Models that support extended thinking (like Gemini 3) can expose their reasoning process. This
 * enum controls how much of that reasoning is included in the response.
 */
public enum ThinkingLevel {
  /** No reasoning trace included. */
  NONE,

  /** Brief reasoning summary. */
  MINIMAL,

  /** Reduced reasoning trace. */
  LOW,

  /** Moderate reasoning detail. */
  MEDIUM,

  /** Full reasoning trace with complete thought process. */
  HIGH
}
