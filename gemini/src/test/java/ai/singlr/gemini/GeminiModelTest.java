/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.gemini.api.OutputAnnotation;
import ai.singlr.gemini.api.OutputItem;
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

  @Test
  void interactionsContentTypeImage() {
    assertEquals("image", GeminiModel.interactionsContentType("image/png"));
    assertEquals("image", GeminiModel.interactionsContentType("image/jpeg"));
    assertEquals("image", GeminiModel.interactionsContentType("image/webp"));
  }

  @Test
  void interactionsContentTypeAudio() {
    assertEquals("audio", GeminiModel.interactionsContentType("audio/mp3"));
    assertEquals("audio", GeminiModel.interactionsContentType("audio/wav"));
  }

  @Test
  void interactionsContentTypeVideo() {
    assertEquals("video", GeminiModel.interactionsContentType("video/mp4"));
  }

  @Test
  void interactionsContentTypeDocument() {
    assertEquals("document", GeminiModel.interactionsContentType("application/pdf"));
    assertEquals("document", GeminiModel.interactionsContentType("text/plain"));
    assertEquals("document", GeminiModel.interactionsContentType("application/json"));
  }

  @Test
  void extractCitationsFromUrlAnnotations() {
    var annotation = new OutputAnnotation("url_citation", "https://example.com", "Example", 0, 10);
    var output =
        new OutputItem("text", "Some text", null, null, null, null, null, List.of(annotation));
    var citations = GeminiModel.extractCitations(List.of(output));

    assertEquals(1, citations.size());
    var citation = citations.getFirst();
    assertEquals("https://example.com", citation.sourceId());
    assertEquals("Example", citation.title());
    assertEquals(0, citation.startIndex());
    assertEquals(10, citation.endIndex());
  }

  @Test
  void extractCitationsSkipsNonUrlCitation() {
    var annotation = new OutputAnnotation("file_citation", "file://local", "Local File", 0, 5);
    var output =
        new OutputItem("text", "Some text", null, null, null, null, null, List.of(annotation));
    var citations = GeminiModel.extractCitations(List.of(output));

    assertTrue(citations.isEmpty());
  }

  @Test
  void extractCitationsFromNullOutputs() {
    var citations = GeminiModel.extractCitations(null);

    assertTrue(citations.isEmpty());
  }

  @Test
  void extractCitationsSkipsNonTextOutputs() {
    var annotation = new OutputAnnotation("url_citation", "https://example.com", "Example", 0, 10);
    var output =
        new OutputItem("thought", null, "summary", null, null, null, null, List.of(annotation));
    var citations = GeminiModel.extractCitations(List.of(output));

    assertTrue(citations.isEmpty());
  }

  @Test
  void extractCitationsMultipleOutputsAndAnnotations() {
    var ann1 = new OutputAnnotation("url_citation", "https://a.com", "A", 0, 5);
    var ann2 = new OutputAnnotation("url_citation", "https://b.com", "B", 10, 20);
    var output1 = new OutputItem("text", "First", null, null, null, null, null, List.of(ann1));
    var output2 = new OutputItem("text", "Second", null, null, null, null, null, List.of(ann2));
    var citations = GeminiModel.extractCitations(List.of(output1, output2));

    assertEquals(2, citations.size());
    assertEquals("https://a.com", citations.get(0).sourceId());
    assertEquals("https://b.com", citations.get(1).sourceId());
  }

  @Test
  void extractCitationsFromOutputWithNoAnnotations() {
    var output = new OutputItem("text", "No sources here", null, null, null, null, null, null);
    var citations = GeminiModel.extractCitations(List.of(output));

    assertTrue(citations.isEmpty());
  }

  @Test
  void urlContextWithFunctionToolsThrows() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withUrlContext(true).build();
    var model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);

    var tool =
        Tool.newBuilder()
            .withName("test_tool")
            .withDescription("A test tool")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("input")
                    .withType(ParameterType.STRING)
                    .withDescription("input")
                    .withRequired(true)
                    .build())
            .withExecutor(args -> ToolResult.success("ok"))
            .build();

    var messages = List.of(Message.user("Hello"));

    assertThrows(IllegalStateException.class, () -> model.chat(messages, List.of(tool)));
  }
}
