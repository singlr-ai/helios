/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnthropicModelIdTest {

  @Test
  void enumHasCorrectId() {
    assertEquals("claude-opus-4-6", AnthropicModelId.CLAUDE_OPUS_4_6.id());
    assertEquals("claude-sonnet-4-6", AnthropicModelId.CLAUDE_SONNET_4_6.id());
  }

  @Test
  void contextWindowValues() {
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_OPUS_4_6.contextWindow());
    assertEquals(1_000_000, AnthropicModelId.CLAUDE_SONNET_4_6.contextWindow());
  }

  @Test
  void fromIdReturnsCorrectModel() {
    assertEquals(AnthropicModelId.CLAUDE_OPUS_4_6, AnthropicModelId.fromId("claude-opus-4-6"));
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6, AnthropicModelId.fromId("claude-sonnet-4-6"));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(AnthropicModelId.fromId("unknown-model"));
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(AnthropicModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(AnthropicModelId.fromId(""));
    assertNull(AnthropicModelId.fromId("   "));
  }

  @Test
  void isSupportedReturnsTrueForKnownModels() {
    assertTrue(AnthropicModelId.isSupported("claude-opus-4-6"));
    assertTrue(AnthropicModelId.isSupported("claude-sonnet-4-6"));
  }

  @Test
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(AnthropicModelId.isSupported("unknown-model"));
    assertFalse(AnthropicModelId.isSupported(null));
    assertFalse(AnthropicModelId.isSupported(""));
  }

  @Test
  void isSupportedReturnsFalseForOtherProviderModels() {
    assertFalse(AnthropicModelId.isSupported("gpt-4"));
    assertFalse(AnthropicModelId.isSupported("gemini-3-flash-preview"));
  }
}
