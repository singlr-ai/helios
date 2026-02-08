/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

/**
 * Tool choice configuration for the Interactions API.
 *
 * @param mode the tool choice mode ("auto", "any", "none", "validated")
 * @param allowedTools tool names allowed when mode is "validated"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolChoiceConfig(
    String mode, @JsonProperty("allowed_tools") Set<String> allowedTools) {

  public static ToolChoiceConfig auto() {
    return new ToolChoiceConfig("auto", null);
  }

  public static ToolChoiceConfig any() {
    return new ToolChoiceConfig("any", null);
  }

  public static ToolChoiceConfig none() {
    return new ToolChoiceConfig("none", null);
  }

  public static ToolChoiceConfig validated(Set<String> allowedTools) {
    return new ToolChoiceConfig("validated", allowedTools);
  }
}
