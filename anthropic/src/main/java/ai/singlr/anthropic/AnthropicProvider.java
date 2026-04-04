/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ModelProvider;

/**
 * ModelProvider implementation for Anthropic's Claude API.
 *
 * <p>Supports Claude Opus 4.6 and Sonnet 4.6 models through the Messages API.
 */
public class AnthropicProvider implements ModelProvider {

  private static final String PROVIDER_NAME = "anthropic";

  @Override
  public String name() {
    return PROVIDER_NAME;
  }

  @Override
  public Model create(String modelId, ModelConfig config) {
    var anthropicModel = AnthropicModelId.fromId(modelId);
    if (anthropicModel == null) {
      throw new IllegalArgumentException("Unsupported model: " + modelId);
    }
    return new AnthropicModel(anthropicModel, config);
  }

  @Override
  public boolean supports(String modelId) {
    return AnthropicModelId.isSupported(modelId);
  }
}
