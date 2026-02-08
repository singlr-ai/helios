/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * An output item from the Interactions API.
 *
 * <p>Can represent text, thoughts, function calls, or other output types.
 *
 * @param type the output type ("text", "thought", "function_call", etc.)
 * @param text text content (for type "text")
 * @param summary thought summary (for type "thought")
 * @param signature thought signature (for type "thought")
 * @param name function name (for type "function_call")
 * @param arguments function arguments (for type "function_call")
 * @param id function call ID (for type "function_call")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputItem(
    String type,
    String text,
    String summary,
    String signature,
    String name,
    Map<String, Object> arguments,
    String id) {

  public boolean isText() {
    return "text".equals(type);
  }

  public boolean isThought() {
    return "thought".equals(type);
  }

  public boolean isFunctionCall() {
    return "function_call".equals(type);
  }
}
