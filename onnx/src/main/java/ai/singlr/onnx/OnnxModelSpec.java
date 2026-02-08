/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.onnx;

import java.util.Map;
import java.util.Optional;

/**
 * Internal metadata for known ONNX models — architecture, dimensions, and prefixes.
 *
 * @param modelType encoder (mean pooling) or decoder (last-token pooling)
 * @param onnxSubfolder subfolder within the HuggingFace repo containing the ONNX file
 * @param sequenceLength maximum input token count for this model
 * @param embeddingDimension output vector dimensionality for this model
 * @param queryPrefix prefix for query embeddings
 * @param documentPrefix prefix for document embeddings
 */
record OnnxModelSpec(
    ModelType modelType,
    String onnxSubfolder,
    int sequenceLength,
    int embeddingDimension,
    String queryPrefix,
    String documentPrefix) {

  enum ModelType {
    ENCODER,
    DECODER
  }

  private static final Map<String, OnnxModelSpec> KNOWN_MODELS =
      Map.of(
          "nomic-ai/nomic-embed-text-v1.5",
          new OnnxModelSpec(ModelType.ENCODER, "onnx", 8192, 768, "", ""),
          "onnx-community/embeddinggemma-300m-ONNX",
          new OnnxModelSpec(
              ModelType.DECODER,
              "onnx",
              2048,
              768,
              "task: search result | query: ",
              "title: none | text: "));

  static Optional<OnnxModelSpec> lookup(String modelName) {
    return Optional.ofNullable(KNOWN_MODELS.get(modelName));
  }

  static boolean isKnown(String modelName) {
    return KNOWN_MODELS.containsKey(modelName);
  }
}
