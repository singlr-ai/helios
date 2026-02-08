/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelsTest {

  @Test
  void providersReturnsListFromServiceLoader() {
    var providers = Models.providers();
    assertTrue(providers instanceof java.util.List<ModelProvider>);
  }

  @Test
  void providerNotFoundReturnsEmpty() {
    var result = Models.provider("nonexistent-provider");
    assertTrue(result.isEmpty());
  }

  @Test
  void createWithUnknownModelThrows() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();

    var exception =
        assertThrows(IllegalArgumentException.class, () -> Models.create("unknown-model", config));
    assertEquals("No provider found for model: unknown-model", exception.getMessage());
  }
}
