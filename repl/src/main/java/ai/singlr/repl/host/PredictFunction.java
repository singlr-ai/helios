/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import java.util.List;
import java.util.Map;

/**
 * Factory for the {@code predict} host function. Calls {@link Model#chat} with a fresh context
 * window (system instruction + user input only), preventing context rot on long-horizon tasks.
 */
public final class PredictFunction {

  private PredictFunction() {}

  /**
   * Create a predict host function backed by the given model.
   *
   * @param model the model to use for predictions
   * @return a host function that sandbox code can call as {@code predict(instructions, input)}
   */
  public static HostFunction create(Model model) {
    if (model == null) {
      throw new IllegalArgumentException("Model must not be null");
    }
    return new HostFunction(
        "predict",
        "Call a language model with fresh context. Parameters: instructions (string), input (string).",
        params -> {
          var instructions = requireString(params, "instructions");
          var input = requireString(params, "input");
          var messages = List.of(Message.system(instructions), Message.user(input));
          var response = model.chat(messages);
          return Map.of("output", response.content() != null ? response.content() : "");
        });
  }

  private static String requireString(Map<String, Object> params, String key) {
    var value = params.get(key);
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Parameter '" + key + "' is required and must be a string");
  }
}
