/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

/**
 * A named host function that sandbox code can call via JSON-RPC.
 *
 * @param name the function name (used as the JSON-RPC method)
 * @param description human-readable description of what the function does
 * @param handler the implementation
 */
public record HostFunction(String name, String description, HostFunctionHandler handler) {

  public HostFunction {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Host function name must not be null or blank");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Host function description must not be null or blank");
    }
    if (handler == null) {
      throw new IllegalArgumentException("Host function handler must not be null");
    }
  }
}
