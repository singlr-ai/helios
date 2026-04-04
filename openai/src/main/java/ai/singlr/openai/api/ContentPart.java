/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Content part within an output message item.
 *
 * <p>Represents text content ({@code output_text}) or a refusal ({@code refusal}) in assistant
 * output messages.
 *
 * @param type the content part type: "output_text", "input_text", or "refusal"
 * @param text the text content
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentPart(String type, String text) {

  public static ContentPart outputText(String text) {
    return new ContentPart("output_text", text);
  }

  public static ContentPart inputText(String text) {
    return new ContentPart("input_text", text);
  }

  public static ContentPart refusal(String text) {
    return new ContentPart("refusal", text);
  }

  public boolean hasTypeOutputText() {
    return "output_text".equals(type);
  }

  public boolean hasTypeInputText() {
    return "input_text".equals(type);
  }

  public boolean hasTypeRefusal() {
    return "refusal".equals(type);
  }
}
