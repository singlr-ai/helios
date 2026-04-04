/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ModelProvider;

/**
 * ModelProvider implementation for OpenAI's GPT API.
 *
 * <p>Supports GPT-5.4, GPT-4.1, GPT-4o, o3, and o4-mini models through the Responses API.
 */
public class OpenAIProvider implements ModelProvider {

  private static final String PROVIDER_NAME = "openai";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    var openaiModel = OpenAIModelId.fromId(modelId);
    if (openaiModel == null) {
      throw new IllegalArgumentException("Unsupported model: " + modelId);
    }
    return new OpenAIModel(openaiModel, config);
  }

  @Override
  public boolean supports(String modelId) {
    return OpenAIModelId.isSupported(modelId);
  }
}
