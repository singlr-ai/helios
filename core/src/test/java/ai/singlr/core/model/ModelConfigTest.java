/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelConfigTest {

  @Test
  void ofFactoryMethod() {
    var config = ModelConfig.of("test-api-key");

    assertEquals("test-api-key", config.apiKey());
    assertEquals(ThinkingLevel.NONE, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderWithAllFields() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("my-api-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .withConnectTimeout(Duration.ofSeconds(30))
            .withResponseTimeout(Duration.ofMinutes(2))
            .build();

    assertEquals("my-api-key", config.apiKey());
    assertEquals(ThinkingLevel.HIGH, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(30), config.connectTimeout());
    assertEquals(Duration.ofMinutes(2), config.responseTimeout());
  }

  @Test
  void builderWithDefaults() {
    var config = ModelConfig.newBuilder().withApiKey("key").build();

    assertEquals("key", config.apiKey());
    assertEquals(ThinkingLevel.NONE, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderPartialOverride() {
    var config =
        ModelConfig.newBuilder().withApiKey("key").withThinkingLevel(ThinkingLevel.MEDIUM).build();

    assertEquals(ThinkingLevel.MEDIUM, config.thinkingLevel());
    assertEquals(Duration.ofSeconds(10), config.connectTimeout());
    assertEquals(Duration.ofSeconds(60), config.responseTimeout());
  }

  @Test
  void builderWithGenerationParams() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withTemperature(0.7)
            .withTopP(0.9)
            .withMaxOutputTokens(1024)
            .withStopSequences(List.of("END", "STOP"))
            .withSeed(42L)
            .build();

    assertEquals(0.7, config.temperature());
    assertEquals(0.9, config.topP());
    assertEquals(1024, config.maxOutputTokens());
    assertEquals(List.of("END", "STOP"), config.stopSequences());
    assertEquals(42L, config.seed());
  }

  @Test
  void builderWithToolChoice() {
    var config =
        ModelConfig.newBuilder().withApiKey("key").withToolChoice(ToolChoice.any()).build();

    assertEquals(ToolChoice.any(), config.toolChoice());
  }

  @Test
  void copyBuilder() {
    var original =
        ModelConfig.newBuilder()
            .withApiKey("key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .withTemperature(0.5)
            .withMaxOutputTokens(512)
            .build();

    var copy = ModelConfig.newBuilder(original).withTemperature(0.9).build();

    assertEquals("key", copy.apiKey());
    assertEquals(ThinkingLevel.HIGH, copy.thinkingLevel());
    assertEquals(0.9, copy.temperature());
    assertEquals(512, copy.maxOutputTokens());
  }

  @Test
  void generationParamsDefaultToNull() {
    var config = ModelConfig.of("key");

    assertNull(config.temperature());
    assertNull(config.topP());
    assertNull(config.maxOutputTokens());
    assertNull(config.stopSequences());
    assertNull(config.seed());
    assertNull(config.toolChoice());
  }
}
