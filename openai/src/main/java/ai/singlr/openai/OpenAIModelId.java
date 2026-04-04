/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

/**
 * Supported OpenAI model identifiers.
 *
 * <p>Each enum constant maps to a specific model available through the Responses API.
 */
public enum OpenAIModelId {
  GPT_5_4("gpt-5.4", 1_000_000),
  GPT_5_4_MINI("gpt-5.4-mini", 1_000_000),
  GPT_5_4_NANO("gpt-5.4-nano", 1_000_000),
  GPT_4_1("gpt-4.1", 1_000_000),
  GPT_4_1_MINI("gpt-4.1-mini", 1_000_000),
  GPT_4_1_NANO("gpt-4.1-nano", 1_000_000),
  GPT_4O("gpt-4o", 128_000),
  GPT_4O_MINI("gpt-4o-mini", 128_000),
  O3("o3", 200_000),
  O4_MINI("o4-mini", 200_000);

  private final String id;
  private final int contextWindow;

  OpenAIModelId(String id, int contextWindow) {
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
   * Finds an OpenAIModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching OpenAIModelId, or null if not found
   */
  public static OpenAIModelId fromId(String id) {
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
