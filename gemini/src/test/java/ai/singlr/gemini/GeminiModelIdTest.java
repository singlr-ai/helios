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
  }

  @Test
  void fromIdReturnsMatchingEnum() {
    var result = GeminiModelId.fromId("gemini-3-flash-preview");
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW, result);
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
  }

  @Test
  void isSupportedReturnsFalseForUnknownModels() {
    assertFalse(GeminiModelId.isSupported("unknown-model"));
    assertFalse(GeminiModelId.isSupported(null));
    assertFalse(GeminiModelId.isSupported(""));
  }
}
