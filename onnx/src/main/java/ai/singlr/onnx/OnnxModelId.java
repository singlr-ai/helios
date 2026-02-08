/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.onnx;

/**
 * Supported ONNX embedding model identifiers.
 *
 * <p>Each enum constant maps to a known HuggingFace model that can be downloaded and run locally
 * via ONNX Runtime.
 */
public enum OnnxModelId {

  /** Nomic Embed Text v1.5 — encoder model, 768-dim, 8192 max tokens. */
  NOMIC_EMBED_V1_5("nomic-ai/nomic-embed-text-v1.5"),

  /** Embedding Gemma 300M — decoder model, 768-dim, 2048 max tokens. */
  EMBEDDING_GEMMA_300M("onnx-community/embeddinggemma-300m-ONNX");

  private final String id;

  OnnxModelId(String id) {
    this.id = id;
  }

  /**
   * Returns the HuggingFace model identifier string.
   *
   * @return the model ID (e.g., "nomic-ai/nomic-embed-text-v1.5")
   */
  public String id() {
    return id;
  }

  /**
   * Finds an OnnxModelId by its string identifier.
   *
   * @param id the model identifier string
   * @return the matching OnnxModelId, or null if not found
   */
  public static OnnxModelId fromId(String id) {
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
