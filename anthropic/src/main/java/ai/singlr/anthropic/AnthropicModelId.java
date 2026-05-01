/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

/**
 * Supported Anthropic Claude model identifiers.
 *
 * <p>Each enum constant maps to a specific Claude model available through the Messages API.
 */
public enum AnthropicModelId {
  // Opus 4.7 moved to the adaptive thinking shape; older models still use enabled+budget_tokens.
  CLAUDE_OPUS_4_7("claude-opus-4-7", 1_000_000, true),
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000, false),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000, false);

  private final String id;
  private final int contextWindow;
  private final boolean usesAdaptiveThinking;

  AnthropicModelId(String id, int contextWindow, boolean usesAdaptiveThinking) {
    this.id = id;
    this.contextWindow = contextWindow;
    this.usesAdaptiveThinking = usesAdaptiveThinking;
  }

  /**
   * Whether this model uses the new {@code thinking.type=adaptive} + {@code
   * output_config.effort=low|medium|high} request shape (Opus 4.7+) vs. the legacy {@code
   * thinking.type=enabled} + {@code budget_tokens=N} shape (Opus 4.6 and Sonnet 4.6).
   *
   * <p>Opus 4.7 explicitly rejects the legacy shape with {@code "thinking.type.enabled" is not
   * supported for this model}. New models are likely to be adaptive-only — set this {@code true}
   * for any future model when in doubt.
   *
   * @return true when the request must use the adaptive shape
   */
  public boolean usesAdaptiveThinking() {
    return usesAdaptiveThinking;
  }

  /**
   * Returns the API model identifier string.
   *
   * @return the model ID used in API requests
   */
  public String id() {
    return id;
  }

  /**
   * Returns the context window size in tokens.
   *
   * @return the context window size
   */
  public int contextWindow() {
    return contextWindow;
  }

  /**
   * Finds an AnthropicModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching AnthropicModelId, or null if not found
   */
  public static AnthropicModelId fromId(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    for (var model : values()) {
      if (model.id.equals(id)) {
        return model;
      }
    }
    return null;
  }

  /**
   * Checks if the given model ID is supported.
   *
   * @param id the model identifier string
   * @return true if the model is supported
   */
  public static boolean isSupported(String id) {
    return fromId(id) != null;
  }
}
