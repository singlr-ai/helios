/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

/**
 * Supported Gemini model identifiers.
 *
 * <p>Each enum constant maps to a specific Gemini model available through the Interactions API.
 */
public enum GeminiModelId {
  GEMINI_3_FLASH_PREVIEW("gemini-3-flash-preview");

  private final String id;

  GeminiModelId(String id) {
    this.id = id;
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
   * Finds a GeminiModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching GeminiModelId, or null if not found
   */
  public static GeminiModelId fromId(String id) {
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
