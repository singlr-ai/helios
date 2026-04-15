/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import java.util.Map;

/**
 * Static bridge for sandbox code to call host functions. Sandbox code evaluated by JShell calls
 * these static methods directly, which delegate to the running {@link JvmSandboxBootstrap}
 * instance.
 *
 * <p>Usage from sandbox code (after {@code import static} pre-eval):
 *
 * <pre>{@code
 * var answer = predict("Be concise", "What is 2+2?");
 * submit(answer);
 * }</pre>
 */
public final class HostBridge {

  private HostBridge() {}

  /**
   * Call the predict host function with fresh model context.
   *
   * @param instructions system instructions for the model
   * @param input the user input to send to the model
   * @return the model's response text
   * @throws IllegalStateException if called outside a sandbox
   */
  public static String predict(String instructions, String input) {
    var bootstrap = requireBootstrap();
    var result =
        bootstrap.callHost("predict", Map.of("instructions", instructions, "input", input));
    if (result instanceof Map<?, ?> map) {
      var output = map.get("output");
      return output != null ? output.toString() : "";
    }
    return result != null ? result.toString() : "";
  }

  /**
   * Submit the final result from sandbox code.
   *
   * @param output the value to submit
   * @throws IllegalStateException if called outside a sandbox
   */
  public static void submit(Object output) {
    var bootstrap = requireBootstrap();
    bootstrap.callHost("submit", Map.of("output", output));
    bootstrap.setSubmittedValue(output);
  }

  private static JvmSandboxBootstrap requireBootstrap() {
    var bootstrap = JvmSandboxBootstrap.instance();
    if (bootstrap == null) {
      throw new IllegalStateException("HostBridge can only be called from within a sandbox");
    }
    return bootstrap;
  }
}
