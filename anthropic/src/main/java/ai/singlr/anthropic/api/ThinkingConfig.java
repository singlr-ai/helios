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
 * <p>Two request shapes coexist depending on the model:
 *
 * <ul>
 *   <li><b>Legacy</b> (Opus 4.6, Sonnet 4.6): {@code {"type":"enabled","budget_tokens":N}}. Built
 *       via {@link #enabled(int)}.
 *   <li><b>Adaptive</b> (Opus 4.7+): {@code {"type":"adaptive"}} alone, with thinking strength
 *       controlled by a sibling {@code output_config.effort} field on the request. Built via {@link
 *       #adaptive()}; pair with {@link OutputConfig#LOW}, {@link OutputConfig#MEDIUM}, or {@link
 *       OutputConfig#HIGH}.
 * </ul>
 *
 * <p>Opus 4.7 rejects the legacy shape with {@code "thinking.type.enabled" is not supported for
 * this model}. {@link ai.singlr.anthropic.AnthropicModelId#usesAdaptiveThinking()} controls
 * dispatch.
 *
 * @param type {@code "enabled"}, {@code "disabled"}, or {@code "adaptive"}
 * @param budgetTokens maximum tokens for thinking (only for {@code enabled})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(String type, @JsonProperty("budget_tokens") Integer budgetTokens) {

  /** Legacy {@code type=enabled} shape with explicit budget. Used by Opus 4.6 / Sonnet 4.6. */
  public static ThinkingConfig enabled(int budgetTokens) {
    return new ThinkingConfig("enabled", budgetTokens);
  }

  /**
   * New {@code type=adaptive} shape (Opus 4.7+). Effort is set via the request's sibling {@code
   * output_config.effort} field, not on this object.
   */
  public static ThinkingConfig adaptive() {
    return new ThinkingConfig("adaptive", null);
  }

  /** Explicit disabled shape; rarely used since omitting the field has the same effect. */
  public static ThinkingConfig disabled() {
    return new ThinkingConfig("disabled", null);
  }
}
