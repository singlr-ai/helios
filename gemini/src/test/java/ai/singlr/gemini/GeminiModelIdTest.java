/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeminiModelIdTest {

  @Test
  void enumHasCorrectId() {
    assertEquals("gemini-3-flash-preview", GeminiModelId.GEMINI_3_FLASH_PREVIEW.id());
    assertEquals("gemini-3.1-pro-preview", GeminiModelId.GEMINI_3_1_PRO_PREVIEW.id());
    assertEquals("gemini-3.1-flash-lite-preview", GeminiModelId.GEMINI_3_1_FLASH_LITE_PREVIEW.id());
  }

  @Test
  void fromIdReturnsMatchingEnum() {
    assertEquals(
        GeminiModelId.GEMINI_3_FLASH_PREVIEW, GeminiModelId.fromId("gemini-3-flash-preview"));
    assertEquals(
        GeminiModelId.GEMINI_3_1_PRO_PREVIEW, GeminiModelId.fromId("gemini-3.1-pro-preview"));
    assertEquals(
        GeminiModelId.GEMINI_3_1_FLASH_LITE_PREVIEW,
        GeminiModelId.fromId("gemini-3.1-flash-lite-preview"));
  }

  @Test
  void fromIdReturnsNullForUnknown() {
    assertNull(GeminiModelId.fromId("unknown-model"));
  }

  @Test
  void fromIdReturnsNullForNull() {
    assertNull(GeminiModelId.fromId(null));
  }

  @Test
  void fromIdReturnsNullForBlank() {
    assertNull(GeminiModelId.fromId(""));
    assertNull(GeminiModelId.fromId("   "));
  }

  @Test
  void isSupportedReturnsTrueForKnownModels() {
    assertTrue(GeminiModelId.isSupported("gemini-3-flash-preview"));
    assertTrue(GeminiModelId.isSupported("gemini-3.1-pro-preview"));
    assertTrue(GeminiModelId.isSupported("gemini-3.1-flash-lite-preview"));
  }

  @Test
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(GeminiModelId.isSupported("unknown-model"));
    assertFalse(GeminiModelId.isSupported(null));
    assertFalse(GeminiModelId.isSupported(""));
  }
}
