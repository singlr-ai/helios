/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.ServiceLoader;

/**
 * Service Provider Interface (SPI) for model providers.
 *
 * <p>Implementations are discovered via {@link ServiceLoader} mechanism. Each provider module
 * (gemini, anthropic, openai) registers its implementation in {@code
 * META-INF/services/ai.singlr.core.model.ModelProvider}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Discover all providers
 * ServiceLoader<ModelProvider> providers = ServiceLoader.load(ModelProvider.class);
 *
 * // Find specific provider
 * ModelProvider gemini = providers.stream()
 *     .map(ServiceLoader.Provider::get)
 *     .filter(p -> "gemini".equals(p.name()))
 *     .findFirst()
 *     .orElseThrow();
 *
 * // Create a model
 * Model model = gemini.create("gemini-3-flash", config);
 * }</pre>
 */
public interface ModelProvider {

  /**
   * The unique name of this provider.
   *
   * @return provider name (e.g., "gemini", "anthropic", "openai")
   */
  String name();

  /**
   * Create a model instance with the given model ID and configuration.
   *
   * @param modelId the specific model to use (e.g., "gemini-3-flash", "claude-3-opus")
   * @param config provider configuration (API key, timeouts, etc.)
   * @return a configured Model instance
   * @throws IllegalArgumentException if the model ID is not supported by this provider
   */
  Model create(String modelId, ModelConfig config);

  /**
   * Check if this provider supports the given model ID.
   *
   * @param modelId the model identifier to check
   * @return true if this provider can create instances for the given model ID
   */
  boolean supports(String modelId);
}
