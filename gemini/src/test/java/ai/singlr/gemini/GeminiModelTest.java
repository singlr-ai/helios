/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.core.model.ModelConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiModelTest {

  @Test
  void thoughtSignatureDelimiterIsRecordSeparator() {
    assertEquals("\u001E", GeminiModel.SIGNATURE_DELIMITER);
  }

  @Test
  void thoughtSignaturesRoundTripWithNewlines() {
    var signatures = List.of("abc123", "sig\nwith\nnewlines", "def456");

    var joined = String.join(GeminiModel.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiModel.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignaturesRoundTripSingleSignature() {
    var signatures = List.of("single-sig");

    var joined = String.join(GeminiModel.SIGNATURE_DELIMITER, signatures);
    var split = joined.split(GeminiModel.SIGNATURE_DELIMITER);

    assertArrayEquals(signatures.toArray(), split);
  }

  @Test
  void thoughtSignatureDelimiterDoesNotAppearInBase64() {
    assertFalse("aGVsbG8gd29ybGQ=".contains(GeminiModel.SIGNATURE_DELIMITER));
  }

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(IllegalArgumentException.class, () -> new GeminiModel(null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals(GeminiModelId.GEMINI_3_FLASH_PREVIEW.id(), model.id());
  }

  @Test
  void providerReturnsGemini() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
    assertEquals("gemini", model.provider());
  }
}
