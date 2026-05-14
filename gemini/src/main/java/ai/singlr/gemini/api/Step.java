/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A timeline step from the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>Replaces the legacy flat {@code outputs[]} array. A response now exposes a {@code steps[]}
 * array whose elements are typed by the {@link #type()} discriminator:
 *
 * <ul>
 *   <li>{@code user_input}, {@code model_output} — carry a {@link #content()} array of {@link
 *       ContentItem}.
 *   <li>{@code thought} — carries a {@link #summary()} array of {@link ContentItem text items} and
 *       a {@link #signature()} for round-tripping.
 *   <li>{@code function_call} — carries {@link #id()}, {@link #name()}, {@link #arguments()}.
 *   <li>{@code google_search_call} — carries {@link #id()}, {@link #arguments()}, {@link
 *       #signature()}.
 *   <li>{@code google_search_result} — carries {@link #callId()}, {@link #result()}, {@link
 *       #signature()}.
 * </ul>
 *
 * @param type the step discriminator
 * @param content content items (for {@code user_input}, {@code model_output})
 * @param summary thought summary items (for {@code thought})
 * @param signature thought / tool-call signature for round-tripping
 * @param id call identifier (for {@code function_call}, {@code google_search_call})
 * @param name function name (for {@code function_call})
 * @param arguments call arguments (for {@code function_call}, {@code google_search_call})
 * @param callId reference to the originating call (for {@code google_search_result})
 * @param result server-tool result payload (for {@code google_search_result}); the wire shape is a
 *     {@code List} of result chips (each carrying {@code search_suggestions} HTML), but older
 *     fixtures and the doc example show a single object — kept as {@link Object} for compatibility
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Step(
    String type,
    List<ContentItem> content,
    List<ContentItem> summary,
    String signature,
    String id,
    String name,
    @JsonDeserialize(using = ArgumentsDeserializer.class) Map<String, Object> arguments,
    @JsonProperty("call_id") String callId,
    Object result) {

  public boolean isModelOutput() {
    return "model_output".equals(type);
  }

  public boolean isUserInput() {
    return "user_input".equals(type);
  }

  public boolean isThought() {
    return "thought".equals(type);
  }

  public boolean isFunctionCall() {
    return "function_call".equals(type);
  }

  public boolean isGoogleSearchCall() {
    return "google_search_call".equals(type);
  }

  public boolean isGoogleSearchResult() {
    return "google_search_result".equals(type);
  }

  public boolean hasContent() {
    return content != null && !content.isEmpty();
  }

  public boolean hasSummary() {
    return summary != null && !summary.isEmpty();
  }
}
