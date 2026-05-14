/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

/**
 * Thrown when {@link LlmReflectiveMutator} cannot produce a usable revised candidate after
 * post-processing and a schema-constrained retry. The optimizer driver decides whether to skip the
 * iteration or abort — the primitive does not.
 */
public class ReflectionFailedException extends RuntimeException {

  public ReflectionFailedException(String message) {
    super(message);
  }

  public ReflectionFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
