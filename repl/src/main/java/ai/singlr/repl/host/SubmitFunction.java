/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory for the {@code submit} host function. Captures the final output from sandbox code,
 * signaling that the task is complete.
 */
public final class SubmitFunction {

  private SubmitFunction() {}

  /**
   * Create a submit host function that stores the submitted value in the given holder.
   *
   * @param holder atomic reference to store the submitted value
   * @return a host function that sandbox code can call as {@code submit(output)}
   */
  public static HostFunction create(AtomicReference<Object> holder) {
    if (holder == null) {
      throw new IllegalArgumentException("Holder must not be null");
    }
    return new HostFunction(
        "submit",
        "Submit the final result. Parameters: output (any). Can only be called once.",
        params -> {
          var output = params.get("output");
          if (output == null) {
            throw new IllegalArgumentException("Parameter 'output' is required");
          }
          if (!holder.compareAndSet(null, output)) {
            throw new IllegalStateException("submit() has already been called");
          }
          return Map.of("status", "accepted");
        });
  }
}
