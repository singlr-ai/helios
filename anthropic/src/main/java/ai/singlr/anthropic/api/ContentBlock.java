/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Content block in Claude Messages API.
 *
 * <p>Represents text, tool_use, tool_result, or thinking content blocks used in both request
 * messages and response content.
 *
 * @param type the block type: "text", "tool_use", "tool_result", or "thinking"
 * @param text text content (for type "text")
 * @param id tool use ID (for type "tool_use")
 * @param name tool name (for type "tool_use")
 * @param input tool arguments (for type "tool_use")
 * @param toolUseId the tool_use ID this result responds to (for type "tool_result")
 * @param content result content (for type "tool_result")
 * @param thinking thinking text (for type "thinking")
 * @param signature cryptographic signature for thinking round-trip (for type "thinking")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentBlock(
    String type,
    String text,
    String id,
    String name,
    Map<String, Object> input,
    @JsonProperty("tool_use_id") String toolUseId,
    String content,
    String thinking,
    String signature) {

  public static ContentBlock text(String text) {
    return new ContentBlock("text", text, null, null, null, null, null, null, null);
  }

  public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
    return new ContentBlock("tool_use", null, id, name, input, null, null, null, null);
  }

  public static ContentBlock toolResult(String toolUseId, String content) {
    return new ContentBlock("tool_result", null, null, null, null, toolUseId, content, null, null);
  }

  public static ContentBlock thinking(String thinking, String signature) {
    return new ContentBlock("thinking", null, null, null, null, null, null, thinking, signature);
  }

  public boolean hasTypeText() {
    return "text".equals(type);
  }

  public boolean hasTypeToolUse() {
    return "tool_use".equals(type);
  }

  public boolean hasTypeToolResult() {
    return "tool_result".equals(type);
  }

  public boolean hasTypeThinking() {
    return "thinking".equals(type);
  }
}
