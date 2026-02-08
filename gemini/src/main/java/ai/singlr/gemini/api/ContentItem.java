/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A content item in the Interactions API.
 *
 * <p>Can represent text, function calls, function results, thoughts, or other content types.
 *
 * @param type the content type ("text", "function_call", "function_result", "thought", etc.)
 * @param text text content (for type "text")
 * @param name function name (for function_call or function_result)
 * @param arguments function arguments (for function_call)
 * @param id function call ID (for function_call)
 * @param callId function call ID reference (for function_result)
 * @param result function result (for function_result)
 * @param signature thought signature for round-tripping (for type "thought")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentItem(
    String type,
    String text,
    String name,
    Map<String, Object> arguments,
    String id,
    @JsonProperty("call_id") String callId,
    Object result,
    String signature) {

  public static ContentItem text(String text) {
    return new ContentItem("text", text, null, null, null, null, null, null);
  }

  public static ContentItem functionCall(String name, Map<String, Object> arguments, String id) {
    return new ContentItem("function_call", null, name, arguments, id, null, null, null);
  }

  public static ContentItem functionResult(String name, String callId, Object result) {
    return new ContentItem("function_result", null, name, null, null, callId, result, null);
  }

  public static ContentItem thought(String signature) {
    return new ContentItem("thought", null, null, null, null, null, null, signature);
  }

  public boolean hasTypeText() {
    return "text".equals(type);
  }

  public boolean hasTypeFunctionCall() {
    return "function_call".equals(type);
  }

  public boolean hasTypeFunctionResult() {
    return "function_result".equals(type);
  }
}
