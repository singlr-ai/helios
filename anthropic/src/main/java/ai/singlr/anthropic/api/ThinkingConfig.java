/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Extended thinking configuration for the Claude Messages API.
 *
 * @param type "enabled" or "disabled"
 * @param budgetTokens maximum tokens for thinking (required when enabled)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(String type, @JsonProperty("budget_tokens") Integer budgetTokens) {

  public static ThinkingConfig enabled(int budgetTokens) {
    return new ThinkingConfig("enabled", budgetTokens);
  }

  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null);
  }
}
