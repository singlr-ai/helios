/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

/**
 * Raised when {@link JsonlCodec} cannot encode or decode a JSONL line. Package-private because the
 * codec is itself package-private; {@link FileExperimentLog} wraps these with more useful messages
 * before surfacing anything to callers.
 */
final class JsonlCodecException extends RuntimeException {

  JsonlCodecException(String message) {
    super(message);
  }

  JsonlCodecException(String message, Throwable cause) {
    super(message, cause);
  }
}
