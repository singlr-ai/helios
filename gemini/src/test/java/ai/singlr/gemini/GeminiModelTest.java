/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.gemini.api.OutputAnnotation;
import ai.singlr.gemini.api.OutputItem;
import java.util.List;
import java.util.Map;
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

  private GeminiModel createModel() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    return new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  @Test
  void convertMessagesSingleToolNotCoalesced() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("search")
                        .withArguments(Map.of("q", "test"))
                        .build())),
            Message.tool("c1", "search", "result1"));

    var converted = model.convertMessages(messages);

    assertEquals(3, converted.turns().size());
    assertEquals("user", converted.turns().get(0).role());
    assertEquals("model", converted.turns().get(1).role());
    assertEquals("user", converted.turns().get(2).role());
    assertEquals(1, converted.turns().get(2).content().size());
    assertTrue(converted.turns().get(2).content().getFirst().hasTypeFunctionResult());
  }

  @Test
  void convertMessagesCoalescesConsecutiveToolMessages() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Get quotes"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "AAPL"))
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "NVDA"))
                        .build())),
            Message.tool("c1", "quote", "AAPL: $228"),
            Message.tool("c2", "quote", "NVDA: $480"));

    var converted = model.convertMessages(messages);

    assertEquals(3, converted.turns().size());
    assertEquals("user", converted.turns().get(0).role());
    assertEquals("model", converted.turns().get(1).role());
    assertEquals("user", converted.turns().get(2).role());

    var coalescedContent = converted.turns().get(2).content();
    assertEquals(2, coalescedContent.size());
    assertTrue(coalescedContent.get(0).hasTypeFunctionResult());
    assertEquals("quote", coalescedContent.get(0).name());
    assertEquals("c1", coalescedContent.get(0).callId());
    assertEquals("AAPL: $228", coalescedContent.get(0).result());
    assertTrue(coalescedContent.get(1).hasTypeFunctionResult());
    assertEquals("quote", coalescedContent.get(1).name());
    assertEquals("c2", coalescedContent.get(1).callId());
    assertEquals("NVDA: $480", coalescedContent.get(1).result());
  }

  @Test
  void convertMessagesCoalescesThreeToolMessages() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Get data"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("a")
                        .withArguments(Map.of())
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("b")
                        .withArguments(Map.of())
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c3")
                        .withName("c")
                        .withArguments(Map.of())
                        .build())),
            Message.tool("c1", "a", "r1"),
            Message.tool("c2", "b", "r2"),
            Message.tool("c3", "c", "r3"));

    var converted = model.convertMessages(messages);

    assertEquals(3, converted.turns().size());
    var coalescedContent = converted.turns().get(2).content();
    assertEquals(3, coalescedContent.size());
    assertEquals("c1", coalescedContent.get(0).callId());
    assertEquals("c2", coalescedContent.get(1).callId());
    assertEquals("c3", coalescedContent.get(2).callId());
  }

  @Test
  void convertMessagesNoConsecutiveUserTurns() {
    var model = createModel();
    var messages =
        List.of(
            Message.user("Hello"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("t1")
                        .withArguments(Map.of())
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("t2")
                        .withArguments(Map.of())
                        .build())),
            Message.tool("c1", "t1", "r1"),
            Message.tool("c2", "t2", "r2"),
            Message.assistant("Done"));

    var converted = model.convertMessages(messages);

    for (int i = 1; i < converted.turns().size(); i++) {
      var prev = converted.turns().get(i - 1).role();
      var curr = converted.turns().get(i).role();
      assertFalse(
          prev.equals(curr) && "user".equals(curr),
          "Consecutive user turns at index " + (i - 1) + " and " + i);
    }
  }

  @Test
  void convertMessagesExtractsSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.system("Be helpful"), Message.user("Hi"));

    var converted = model.convertMessages(messages);

    assertEquals("Be helpful", converted.systemInstruction());
    assertEquals(1, converted.turns().size());
    assertEquals("user", converted.turns().getFirst().role());
  }

  @Test
  void convertMessagesNoSystemInstruction() {
    var model = createModel();
    var messages = List.of(Message.user("Hi"));

    var converted = model.convertMessages(messages);

    assertNull(converted.systemInstruction());
    assertEquals(1, converted.turns().size());
  }

  @Test
  void convertMessagesMultiTurnWithToolCoalescing() {
    var model = createModel();
    var messages =
        List.of(
            Message.system("You are an analyst"),
            Message.user("Analyze portfolio"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c1")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "AAPL"))
                        .build(),
                    ToolCall.newBuilder()
                        .withId("c2")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "NVDA"))
                        .build())),
            Message.tool("c1", "quote", "AAPL: $228"),
            Message.tool("c2", "quote", "NVDA: $480"),
            Message.assistant("Your portfolio looks good"),
            Message.user("What about MSFT?"),
            Message.assistant(
                List.of(
                    ToolCall.newBuilder()
                        .withId("c3")
                        .withName("quote")
                        .withArguments(Map.of("ticker", "MSFT"))
                        .build())),
            Message.tool("c3", "quote", "MSFT: $420"),
            Message.assistant("MSFT is strong"));

    var converted = model.convertMessages(messages);

    assertEquals("You are an analyst", converted.systemInstruction());
    assertEquals(8, converted.turns().size());
    assertEquals("user", converted.turns().get(0).role());
    assertEquals("model", converted.turns().get(1).role());
    assertEquals("user", converted.turns().get(2).role());
    assertEquals(2, converted.turns().get(2).content().size());
    assertEquals("model", converted.turns().get(3).role());
    assertEquals("user", converted.turns().get(4).role());
    assertEquals("model", converted.turns().get(5).role());
    assertEquals("user", converted.turns().get(6).role());
    assertEquals(1, converted.turns().get(6).content().size());
    assertEquals("model", converted.turns().get(7).role());
  }
}
