/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenAIModelIdTest {

  @Test
  void gpt54Id() {
    assertEquals("gpt-5.4", OpenAIModelId.GPT_5_4.id());
  }

  @Test
  void gpt54MiniId() {
    assertEquals("gpt-5.4-mini", OpenAIModelId.GPT_5_4_MINI.id());
  }

  @Test
  void gpt54NanoId() {
    assertEquals("gpt-5.4-nano", OpenAIModelId.GPT_5_4_NANO.id());
  }

  @Test
  void gpt41Id() {
    assertEquals("gpt-4.1", OpenAIModelId.GPT_4_1.id());
  }

  @Test
  void gpt41MiniId() {
    assertEquals("gpt-4.1-mini", OpenAIModelId.GPT_4_1_MINI.id());
  }

  @Test
  void gpt41NanoId() {
    assertEquals("gpt-4.1-nano", OpenAIModelId.GPT_4_1_NANO.id());
  }

  @Test
  void gpt4oId() {
    assertEquals("gpt-4o", OpenAIModelId.GPT_4O.id());
  }

  @Test
  void gpt4oMiniId() {
    assertEquals("gpt-4o-mini", OpenAIModelId.GPT_4O_MINI.id());
  }

  @Test
  void o3Id() {
    assertEquals("o3", OpenAIModelId.O3.id());
  }

  @Test
  void o4MiniId() {
    assertEquals("o4-mini", OpenAIModelId.O4_MINI.id());
  }

  @Test
  void gpt54ContextWindow() {
    assertEquals(1_000_000, OpenAIModelId.GPT_5_4.contextWindow());
  }

  @Test
  void gpt4oContextWindow() {
    assertEquals(128_000, OpenAIModelId.GPT_4O.contextWindow());
  }

  @Test
  void o3ContextWindow() {
    assertEquals(200_000, OpenAIModelId.O3.contextWindow());
  }

  @Test
  void fromIdFindsKnownModel() {
    var model = OpenAIModelId.fromId("gpt-4o");
    assertNotNull(model);
    assertEquals(OpenAIModelId.GPT_4O, model);
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(OpenAIModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(OpenAIModelId.fromId("   "));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(OpenAIModelId.fromId("davinci-003"));
  }

  @Test
  void isSupportedKnownModel() {
    assertTrue(OpenAIModelId.isSupported("gpt-4.1"));
  }

  @Test
  void isSupportedUnknownModel() {
    assertFalse(OpenAIModelId.isSupported("claude-sonnet-4-6"));
  }

  @Test
  void allModelsHaveIds() {
    for (var model : OpenAIModelId.values()) {
      assertNotNull(model.id());
      assertFalse(model.id().isBlank());
      assertTrue(model.contextWindow() > 0);
    }
  }
}
