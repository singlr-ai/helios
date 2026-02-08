/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovery facade for {@link ModelProvider} instances registered via JPMS {@link ServiceLoader}.
 *
 * <p>Provider modules declare themselves with {@code provides ai.singlr.core.model.ModelProvider
 * with ...} in their {@code module-info.java}. This class loads and queries those providers.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Auto-discover provider and create model
 * Model model = Models.create("gemini-2.0-flash", config);
 *
 * // Explicit provider lookup
 * ModelProvider gemini = Models.provider("gemini").orElseThrow();
 * Model model = gemini.create("gemini-2.0-flash", config);
 *
 * // List all available providers
 * List<ModelProvider> all = Models.providers();
 * }</pre>
 */
public final class Models {

  private Models() {}

  /**
   * List all available model providers discovered via ServiceLoader.
   *
   * @return immutable list of all registered providers
   */
  public static List<ModelProvider> providers() {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .toList();
  }

  /**
   * Find a provider by name.
   *
   * @param name the provider name (e.g., "gemini", "anthropic")
   * @return the provider, or empty if not found
   */
  public static Optional<ModelProvider> provider(String name) {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.name().equals(name))
        .findFirst();
  }

  /**
   * Create a model by auto-discovering which provider supports the given model ID.
   *
   * @param modelId the model identifier (e.g., "gemini-2.0-flash", "claude-3-opus")
   * @param config provider configuration
   * @return the created model
   * @throws IllegalArgumentException if no provider supports the model ID
   */
  public static Model create(String modelId, ModelConfig config) {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.supports(modelId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No provider found for model: " + modelId))
        .create(modelId, config);
  }
}
