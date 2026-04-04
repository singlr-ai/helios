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
  CLAUDE_OPUS_4_6("claude-opus-4-6", 1_000_000),
  CLAUDE_SONNET_4_6("claude-sonnet-4-6", 1_000_000);

  private final String id;
  private final int contextWindow;

  AnthropicModelId(String id, int contextWindow) {
    this.id = id;
    this.contextWindow = contextWindow;
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
