/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A content item in the Interactions API.
 *
 * <p>Used both for request-side {@link Turn#content()} and for the {@code content} array inside
 * response-side {@code model_output} and {@code user_input} steps.
 *
 * <p>Can represent text (optionally annotated with grounding citations), function calls, function
 * results, thoughts, or inline data.
 *
 * @param type the content type ({@code text}, {@code function_call}, {@code function_result},
 *     {@code thought}, {@code image}, {@code document}, {@code audio}, {@code video}, ...)
 * @param text text content (for type {@code text})
 * @param name function name (for {@code function_call} or {@code function_result})
 * @param arguments function arguments (for {@code function_call})
 * @param id function call ID (for {@code function_call})
 * @param callId function call ID reference (for {@code function_result})
 * @param result function result (for {@code function_result})
 * @param signature thought signature for round-tripping (for type {@code thought})
 * @param mimeType MIME type for inline data (e.g., {@code image/png}, {@code application/pdf})
 * @param data Base64-encoded inline data
 * @param annotations source annotations attached to text content (e.g. {@code url_citation} entries
 *     from Google Search grounding); only populated on response-side text items
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
    String signature,
    @JsonProperty("mime_type") String mimeType,
    String data,
    List<OutputAnnotation> annotations) {

  public static ContentItem text(String text) {
    return new ContentItem("text", text, null, null, null, null, null, null, null, null, null);
  }

  public static ContentItem inlineData(String type, String mimeType, String base64Data) {
    return new ContentItem(
        type, null, null, null, null, null, null, null, mimeType, base64Data, null);
  }

  public static ContentItem functionCall(String name, Map<String, Object> arguments, String id) {
    return new ContentItem(
        "function_call", null, name, arguments, id, null, null, null, null, null, null);
  }

  public static ContentItem functionResult(String name, String callId, Object result) {
    return new ContentItem(
        "function_result", null, name, null, null, callId, result, null, null, null, null);
  }

  public static ContentItem thought(String signature) {
    return new ContentItem(
        "thought", null, null, null, null, null, null, signature, null, null, null);
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

  public boolean hasAnnotations() {
    return annotations != null && !annotations.isEmpty();
  }
}
