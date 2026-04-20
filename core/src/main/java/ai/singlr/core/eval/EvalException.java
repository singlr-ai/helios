/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

/**
 * Raised when an {@link Evaluator} run cannot complete — a worker task threw an unrecoverable
 * exception or the evaluator was interrupted. Mirrors the house pattern used by other modules (e.g.
 * {@code PgException}, {@code ReplException}, {@code AnthropicException}).
 */
public final class EvalException extends RuntimeException {

  public EvalException(String message, Throwable cause) {
    super(message, cause);
  }

  public EvalException(String message) {
    super(message);
  }
}
