/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.anthropic.api.ContentBlock;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicModelTest {

  @Test
  void constructorRequiresModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertThrows(IllegalArgumentException.class, () -> new AnthropicModel(null, config));
  }

  @Test
  void constructorRequiresConfig() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, null));
  }

  @Test
  void constructorRequiresApiKey() {
    var config = ModelConfig.newBuilder().build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void constructorRequiresNonBlankApiKey() {
    var config = ModelConfig.newBuilder().withApiKey("   ").build();
    assertThrows(
        IllegalArgumentException.class,
        () -> new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config));
  }

  @Test
  void idReturnsModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals(AnthropicModelId.CLAUDE_SONNET_4_6.id(), model.id());
  }

  @Test
  void providerReturnsAnthropic() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    assertEquals("anthropic", model.provider());
  }

  @Test
  void contextWindowReturnsModelValue() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);
    assertEquals(1_000_000, model.contextWindow());
  }

  @Test
  void buildRequestExtractsSystemMessage() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var messages = List.of(Message.system("You are helpful"), Message.user("Hello"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals("You are helpful", request.system());
    assertEquals(1, request.messages().size());
    assertEquals("user", request.messages().getFirst().role());
  }

  @Test
  void buildRequestCoalescesToolMessages() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var toolCalls =
        List.of(
            ToolCall.newBuilder().withId("call_1").withName("tool1").build(),
            ToolCall.newBuilder().withId("call_2").withName("tool2").build());
    var messages =
        List.of(
            Message.user("Do something"),
            Message.assistant("Sure", toolCalls),
            Message.tool("call_1", "tool1", "result1"),
            Message.tool("call_2", "tool2", "result2"));

    var request = model.buildRequest(messages, List.of(), null);

    assertEquals(3, request.messages().size());
    assertEquals("user", request.messages().get(0).role());
    assertEquals("assistant", request.messages().get(1).role());
    assertEquals("user", request.messages().get(2).role());

    @SuppressWarnings("unchecked")
    var toolResults = (List<ContentBlock>) request.messages().get(2).content();
    assertEquals(2, toolResults.size());
    assertTrue(toolResults.get(0).hasTypeToolResult());
    assertEquals("call_1", toolResults.get(0).toolUseId());
    assertEquals("result1", toolResults.get(0).content());
    assertTrue(toolResults.get(1).hasTypeToolResult());
    assertEquals("call_2", toolResults.get(1).toolUseId());
    assertEquals("result2", toolResults.get(1).content());
  }

  @Test
  void buildRequestWithTools() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get weather")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withDescription("City name")
                    .withRequired(true)
                    .build())
            .withExecutor(args -> ToolResult.success("sunny"))
            .build();

    var messages = List.of(Message.user("Weather?"));
    var request = model.buildRequest(messages, List.of(tool), null);

    assertNotNull(request.tools());
    assertEquals(1, request.tools().size());
    assertEquals("get_weather", request.tools().getFirst().name());
    assertEquals("Get weather", request.tools().getFirst().description());
    assertNotNull(request.tools().getFirst().inputSchema());
  }

  @Test
  void buildRequestDefaultMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(4096, request.maxTokens());
  }

  @Test
  void buildRequestCustomMaxTokens() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").withMaxOutputTokens(8192).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(8192, request.maxTokens());
  }

  @Test
  void buildRequestWithThinking() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MEDIUM)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Think")), List.of(), null);

    assertNotNull(request.thinking());
    assertEquals("enabled", request.thinking().type());
    assertEquals(10000, request.thinking().budgetTokens());
    assertNull(request.temperature());
    assertTrue(request.maxTokens() >= 10000 + 1024);
  }

  @Test
  void buildRequestThinkingNoneOmitsConfig() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.NONE)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.thinking());
  }

  @Test
  void buildRequestThinkingMinimal() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.MINIMAL)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(1024, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingLow() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.LOW)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(4096, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestThinkingHigh() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withThinkingLevel(ThinkingLevel.HIGH)
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(32000, request.thinking().budgetTokens());
  }

  @Test
  void buildRequestWithOutputSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());
    var request = model.buildRequest(List.of(Message.user("Extract")), List.of(), schema);

    assertNotNull(request.system());
    assertTrue(request.system().contains("JSON"));
    assertTrue(request.system().contains("schema"));
  }

  @Test
  void buildRequestToolChoiceAuto() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.auto()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("auto", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceAny() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.any()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("any", request.toolChoice().type());
  }

  @Test
  void buildRequestToolChoiceNoneReturnsNull() {
    var config =
        ModelConfig.newBuilder().withApiKey("test-key").withToolChoice(ToolChoice.none()).build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("test")
            .withDescription("test")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNull(request.toolChoice());
  }

  @Test
  void buildRequestToolChoiceRequiredSingle() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("my_tool"))
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("my_tool")
            .withDescription("test")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();
    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null);

    assertNotNull(request.toolChoice());
    assertEquals("tool", request.toolChoice().type());
    assertEquals("my_tool", request.toolChoice().name());
  }

  @Test
  void buildRequestToolChoiceRequiredMultipleThrows() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withToolChoice(ToolChoice.required("tool1", "tool2"))
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var tool =
        Tool.newBuilder()
            .withName("tool1")
            .withDescription("test")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();

    assertThrows(
        IllegalStateException.class,
        () -> model.buildRequest(List.of(Message.user("Hi")), List.of(tool), null));
  }

  @Test
  void buildRequestWithGenerationParams() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withTemperature(0.7)
            .withTopP(0.9)
            .withStopSequences(List.of("END"))
            .build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals(0.7, request.temperature());
    assertEquals(0.9, request.topP());
    assertEquals(List.of("END"), request.stopSequences());
  }

  @Test
  void buildRequestStreamsAlways() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertTrue(request.stream());
  }

  @Test
  void convertAssistantMessageSimpleText() {
    var message = Message.assistant("Hello");

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("Hello", entry.content());
  }

  @Test
  void convertAssistantMessageWithToolCalls() {
    var tc =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("search")
            .withArguments(Map.of("q", "test"))
            .build();
    var message = Message.assistant("", List.of(tc));

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(1, blocks.size());
    assertTrue(blocks.getFirst().hasTypeToolUse());
    assertEquals("call_1", blocks.getFirst().id());
    assertEquals("search", blocks.getFirst().name());
  }

  @Test
  void convertAssistantMessageWithThinkingSignature() {
    var metadata =
        Map.of(
            AnthropicModel.THINKING_KEY, "I need to think about this",
            AnthropicModel.THINKING_SIGNATURE_KEY, "sig123");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<ContentBlock>) entry.content();
    assertEquals(2, blocks.size());
    assertTrue(blocks.get(0).hasTypeThinking());
    assertEquals("I need to think about this", blocks.get(0).thinking());
    assertEquals("sig123", blocks.get(0).signature());
    assertTrue(blocks.get(1).hasTypeText());
    assertEquals("Answer", blocks.get(1).text());
  }

  @Test
  void convertAssistantMessageWithEmptyThinkingSignatureUsesString() {
    var metadata = Map.of(AnthropicModel.THINKING_SIGNATURE_KEY, "");
    var message = Message.assistant("Answer", List.of(), metadata);

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("Answer", entry.content());
  }

  @Test
  void mapStopReasonEndTurn() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("end_turn"));
  }

  @Test
  void mapStopReasonStopSequence() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("stop_sequence"));
  }

  @Test
  void mapStopReasonToolUse() {
    assertEquals(FinishReason.TOOL_CALLS, AnthropicModel.mapStopReason("tool_use"));
  }

  @Test
  void mapStopReasonMaxTokens() {
    assertEquals(FinishReason.LENGTH, AnthropicModel.mapStopReason("max_tokens"));
  }

  @Test
  void mapStopReasonNull() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason(null));
  }

  @Test
  void mapStopReasonUnknown() {
    assertEquals(FinishReason.STOP, AnthropicModel.mapStopReason("unknown"));
  }

  @Test
  void stripMarkdownWrapperJsonBlock() {
    assertEquals(
        "{\"name\":\"test\"}",
        AnthropicModel.stripMarkdownWrapper("```json\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperPlainBlock() {
    assertEquals(
        "{\"name\":\"test\"}",
        AnthropicModel.stripMarkdownWrapper("```\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperNoWrapper() {
    assertEquals("{\"name\":\"test\"}", AnthropicModel.stripMarkdownWrapper("{\"name\":\"test\"}"));
  }

  @Test
  void buildRequestNoToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestNullToolsReturnsNullToolDefs() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), null, null);

    assertNull(request.tools());
  }

  @Test
  void buildRequestSystemAndOutputSchemaAppended() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);

    var schema = Map.<String, Object>of("type", "object");
    var messages = List.of(Message.system("Be helpful"), Message.user("Extract"));
    var request = model.buildRequest(messages, List.of(), schema);

    assertTrue(request.system().startsWith("Be helpful"));
    assertTrue(request.system().contains("JSON"));
  }

  @Test
  void convertAssistantMessageNullContentBecomesEmpty() {
    var message =
        new Message(
            ai.singlr.core.model.Role.ASSISTANT, null, List.of(), null, null, Map.of(), List.of());

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    assertEquals("", entry.content());
  }

  @Test
  void buildRequestModelId() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var request = model.buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertEquals("claude-opus-4-6", request.model());
  }
}
