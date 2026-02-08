/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - ONNX Embedding Provider Module.
 *
 * <p>Implements the EmbeddingProvider SPI for local ONNX Runtime inference. Downloads models from
 * HuggingFace and runs embedding generation locally.
 */
module ai.singlr.onnx {
  requires ai.singlr.core;
  requires java.logging;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.microsoft.onnxruntime;
  requires ai.djl.tokenizers;

  exports ai.singlr.onnx;

  provides ai.singlr.core.embedding.EmbeddingProvider with
      ai.singlr.onnx.OnnxEmbeddingProvider;
}
