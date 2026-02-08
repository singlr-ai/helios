/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

/** Reason why the model stopped generating. */
public enum FinishReason {
  /** Model finished naturally. */
  STOP,

  /** Model wants to call tools. */
  TOOL_CALLS,

  /** Hit maximum token limit. */
  LENGTH,

  /** Content was filtered. */
  CONTENT_FILTER,

  /** An error occurred. */
  ERROR
}
