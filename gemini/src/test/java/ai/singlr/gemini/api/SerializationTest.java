/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SerializationTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void serializeSimpleRequest() throws Exception {
    var turn = Turn.user("Hello");
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(turn))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"model\":\"gemini-3-flash-preview\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"Hello\""));
    assertFalse(json.contains("functionCall"));
  }

  @Test
  void serializeContentItem() throws Exception {
    var item = ContentItem.text("Hello world");
    var json = objectMapper.writeValueAsString(item);
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"text\":\"Hello world\""));
    assertFalse(json.contains("functionCall"));
    assertFalse(json.contains("\"name\""));
    assertFalse(json.contains("\"arguments\""));
    assertFalse(json.contains("\"annotations\""));
  }

  @Test
  void serializeTurn() throws Exception {
    var turn = Turn.user("Test message");
    var json = objectMapper.writeValueAsString(turn);
    assertTrue(json.contains("\"role\":\"user\""));
    assertTrue(json.contains("\"content\":["));
    assertFalse(json.contains("functionCall"));
  }

  @Test
  void serializeRequestWithToolsMatchingClientProject() throws Exception {
    var searchPeople =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people in the Light DAO community using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Natural language description of who to find")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("pageNumber")
                    .withDescription("Page of results, 1-indexed")
                    .withType(ParameterType.INTEGER)
                    .withRequired(false)
                    .build())
            .withExecutor(args -> ToolResult.success("[]"))
            .build();

    var searchEvents =
        Tool.newBuilder()
            .withName("search_events")
            .withDescription("Finds upcoming Light DAO events using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Topic or theme to search for")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("timeWindow")
                    .withDescription("Time constraint")
                    .withType(ParameterType.STRING)
                    .withRequired(false)
                    .build())
            .withExecutor(args -> ToolResult.success("[]"))
            .build();

    var tools = List.of(searchPeople, searchEvents);
    var toolDefinitions =
        tools.stream()
            .map(
                t -> ToolDefinition.function(t.name(), t.description(), t.parametersAsJsonSchema()))
            .toList();

    var input = new ArrayList<Turn>();
    input.add(Turn.user("Who should I meet?"));

    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withSystemInstruction("You are a helpful assistant.")
            .withInput(input)
            .withTools(toolDefinitions)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"search_people\""));
    assertTrue(json.contains("\"search_events\""));
    assertTrue(json.contains("\"type\":\"function\""));
  }

  @Test
  void serializeToolResultRoundTrip() throws Exception {
    var input = new ArrayList<Turn>();
    input.add(Turn.user("Who should I meet?"));
    input.add(
        Turn.model(
            List.of(
                ContentItem.functionCall(
                    "search_people", Map.of("query", "interesting people"), "call_001"))));
    input.add(
        Turn.user(
            List.of(
                ContentItem.functionResult(
                    "search_people", "call_001", "Found 3 people matching your query"))));

    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(input)
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"type\":\"function_call\""));
    assertTrue(json.contains("\"id\":\"call_001\""));

    var functionCallJson =
        objectMapper.writeValueAsString(ContentItem.functionCall("search_people", Map.of(), "c1"));
    assertTrue(functionCallJson.contains("\"id\":\"c1\""));
    assertFalse(functionCallJson.contains("call_id"), "function_call must not have call_id");

    var functionResultJson =
        objectMapper.writeValueAsString(ContentItem.functionResult("search_people", "c1", "ok"));
    assertTrue(functionResultJson.contains("\"call_id\":\"c1\""));
    assertFalse(functionResultJson.contains("\"id\""), "function_result must not have id");
  }

  @Test
  void serializeInlineDataContentItem() throws Exception {
    var item = ContentItem.inlineData("image", "image/png", "iVBORw0KGgo=");
    var json = objectMapper.writeValueAsString(item);

    assertTrue(json.contains("\"type\":\"image\""));
    assertTrue(json.contains("\"mime_type\":\"image/png\""));
    assertTrue(json.contains("\"data\":\"iVBORw0KGgo=\""));
    assertFalse(json.contains("\"text\""));
    assertFalse(json.contains("\"name\""));
  }

  @Test
  void serializeInlineDataTurnWithText() throws Exception {
    var items =
        List.of(
            ContentItem.inlineData("document", "application/pdf", "JVBERi0="),
            ContentItem.text("Extract text from this PDF"));
    var turn = Turn.user(items);
    var json = objectMapper.writeValueAsString(turn);

    assertTrue(json.contains("\"type\":\"document\""));
    assertTrue(json.contains("\"mime_type\":\"application/pdf\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("Extract text from this PDF"));
  }

  @Test
  void serializeThoughtContentItem() throws Exception {
    var item = ContentItem.thought("sig-abc");
    var json = objectMapper.writeValueAsString(item);

    assertTrue(json.contains("\"type\":\"thought\""));
    assertTrue(json.contains("\"signature\":\"sig-abc\""));
    assertFalse(json.contains("\"text\""));
  }

  @Test
  void contentItemRoundTripWithAnnotations() throws Exception {
    var json =
        """
        {
          "type": "text",
          "text": "According to sources...",
          "annotations": [
            {
              "type": "url_citation",
              "url": "https://example.com",
              "title": "Example Source",
              "start_index": 0,
              "end_index": 25
            }
          ]
        }
        """;

    var item = objectMapper.readValue(json, ContentItem.class);

    assertEquals("text", item.type());
    assertTrue(item.hasTypeText());
    assertTrue(item.hasAnnotations());
    assertEquals(1, item.annotations().size());

    var annotation = item.annotations().getFirst();
    assertEquals("url_citation", annotation.type());
    assertEquals("https://example.com", annotation.url());
    assertEquals("Example Source", annotation.title());
    assertEquals(0, annotation.startIndex());
    assertEquals(25, annotation.endIndex());
  }

  @Test
  void contentItemWithoutAnnotationsHasAnnotationsReturnsFalse() throws Exception {
    var json =
        """
        {
          "type": "text",
          "text": "Hello world"
        }
        """;

    var item = objectMapper.readValue(json, ContentItem.class);

    assertEquals("text", item.type());
    assertFalse(item.hasAnnotations());
  }

  @Test
  void deserializeOutputAnnotation() throws Exception {
    var json =
        """
        {
          "type": "url_citation",
          "url": "https://example.com/page",
          "title": "Page Title",
          "start_index": 10,
          "end_index": 50
        }
        """;

    var annotation = objectMapper.readValue(json, OutputAnnotation.class);

    assertEquals("url_citation", annotation.type());
    assertEquals("https://example.com/page", annotation.url());
    assertEquals("Page Title", annotation.title());
    assertEquals(10, annotation.startIndex());
    assertEquals(50, annotation.endIndex());
  }

  @Test
  void serializeUrlContextToolDefinition() throws Exception {
    var tool = ToolDefinition.urlContext();
    var json = objectMapper.writeValueAsString(tool);

    assertTrue(json.contains("\"type\":\"url_context\""));
    assertFalse(json.contains("\"name\""));
    assertFalse(json.contains("\"description\""));
    assertFalse(json.contains("\"parameters\""));
  }

  @Test
  void serializeCodeExecutionToolDefinition() throws Exception {
    var tool = ToolDefinition.codeExecution();
    var json = objectMapper.writeValueAsString(tool);

    assertTrue(json.contains("\"type\":\"code_execution\""));
    assertFalse(json.contains("\"name\""));
  }

  @Test
  void serializeRequestWithPreviousInteractionId() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("Follow-up")))
            .withPreviousInteractionId("interaction_abc123")
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"previous_interaction_id\":\"interaction_abc123\""));
    assertFalse(json.contains("system_instruction"));
  }

  @Test
  void serializeRequestWithoutPreviousInteractionIdOmitsField() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("Hello")))
            .build();
    var json = objectMapper.writeValueAsString(request);
    assertFalse(json.contains("previous_interaction_id"));
  }

  @Test
  void serializeRequestWithJsonResponseFormat() throws Exception {
    var schema =
        Map.<String, Object>of(
            "type", "object", "properties", Map.of("name", Map.of("type", "string")));
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("Extract")))
            .withResponseFormat(ResponseFormat.json(schema))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"response_format\""));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"mime_type\":\"application/json\""));
    assertTrue(json.contains("\"schema\""));
    assertFalse(
        json.contains("response_mime_type"),
        "response_mime_type was removed in Api-Revision 2026-05-20");
  }

  @Test
  void serializeRequestWithTextResponseFormat() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("Hi")))
            .withResponseFormat(ResponseFormat.text())
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"response_format\":{\"type\":\"text\"}"));
  }

  @Test
  void serializeRequestWithImageResponseFormat() throws Exception {
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("Draw a cat")))
            .withResponseFormat(ResponseFormat.image("image/jpeg", "1:1", "1K"))
            .build();

    var json = objectMapper.writeValueAsString(request);
    assertTrue(json.contains("\"type\":\"image\""));
    assertTrue(json.contains("\"mime_type\":\"image/jpeg\""));
    assertTrue(json.contains("\"aspect_ratio\":\"1:1\""));
    assertTrue(json.contains("\"image_size\":\"1K\""));
  }

  @Test
  void jsonResponseFormatRequiresSchema() {
    assertThrows(IllegalArgumentException.class, () -> ResponseFormat.json(null));
  }

  @Test
  void imageResponseFormatRequiresMimeType() {
    assertThrows(IllegalArgumentException.class, () -> ResponseFormat.image(null, "1:1", "1K"));
    assertThrows(IllegalArgumentException.class, () -> ResponseFormat.image("", "1:1", "1K"));
  }

  @Test
  void deserializeInteractionResponseWithSteps() throws Exception {
    var json =
        """
        {
          "id": "int_123",
          "model": "gemini-3-flash-preview",
          "status": "completed",
          "steps": [
            {
              "type": "model_output",
              "content": [
                {
                  "type": "text",
                  "text": "Result from search",
                  "annotations": [
                    {
                      "type": "url_citation",
                      "url": "https://example.com",
                      "title": "Source",
                      "start_index": 0,
                      "end_index": 18
                    }
                  ]
                }
              ]
            }
          ],
          "usage": {
            "total_input_tokens": 5,
            "total_output_tokens": 3,
            "total_tokens": 8
          }
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertEquals("int_123", response.id());
    assertTrue(response.isCompleted());
    assertNotNull(response.steps());
    assertEquals(1, response.steps().size());

    var step = response.steps().getFirst();
    assertTrue(step.isModelOutput());
    assertTrue(step.hasContent());
    assertEquals(1, step.content().size());

    var text = step.content().getFirst();
    assertTrue(text.hasTypeText());
    assertTrue(text.hasAnnotations());
    assertEquals("url_citation", text.annotations().getFirst().type());
    assertEquals("https://example.com", text.annotations().getFirst().url());

    assertEquals(5, response.usage().inputTokens());
    assertEquals(3, response.usage().outputTokens());
    assertEquals(8, response.usage().totalTokens());
  }

  @Test
  void deserializeFunctionCallAndThoughtSteps() throws Exception {
    var json =
        """
        {
          "id": "int_001",
          "status": "requires_action",
          "steps": [
            {
              "type": "thought",
              "summary": [
                {"type": "text", "text": "I need to check the weather in Boston..."}
              ],
              "signature": "abc123..."
            },
            {
              "type": "function_call",
              "id": "fc_1",
              "name": "get_weather",
              "arguments": { "location": "Boston, MA" }
            }
          ]
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertTrue(response.requiresAction());
    assertEquals(2, response.steps().size());

    var thought = response.steps().get(0);
    assertTrue(thought.isThought());
    assertEquals("abc123...", thought.signature());
    assertTrue(thought.hasSummary());
    assertEquals("I need to check the weather in Boston...", thought.summary().getFirst().text());

    var call = response.steps().get(1);
    assertTrue(call.isFunctionCall());
    assertEquals("fc_1", call.id());
    assertEquals("get_weather", call.name());
    assertEquals("Boston, MA", call.arguments().get("location"));
  }

  @Test
  void deserializeGoogleSearchSteps() throws Exception {
    var json =
        """
        {
          "id": "int_456",
          "status": "completed",
          "steps": [
            {
              "type": "google_search_call",
              "id": "gs_1",
              "arguments": { "queries": ["last Super Bowl winner"] },
              "signature": "sig_call"
            },
            {
              "type": "google_search_result",
              "call_id": "gs_1",
              "result": {"search_suggestions": "<div>...</div>"},
              "signature": "sig_result"
            },
            {
              "type": "model_output",
              "content": [
                {
                  "type": "text",
                  "text": "Kansas City Chiefs.",
                  "annotations": [
                    {
                      "type": "url_citation",
                      "url": "https://www.nfl.com/super-bowl",
                      "title": "NFL.com",
                      "start_index": 0,
                      "end_index": 19
                    }
                  ]
                }
              ]
            }
          ]
        }
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertEquals(3, response.steps().size());
    assertTrue(response.steps().get(0).isGoogleSearchCall());
    assertEquals("sig_call", response.steps().get(0).signature());
    assertTrue(response.steps().get(1).isGoogleSearchResult());
    assertEquals("gs_1", response.steps().get(1).callId());
    assertNotNull(response.steps().get(1).result());
    @SuppressWarnings("unchecked")
    var resultMap = (Map<String, Object>) response.steps().get(1).result();
    assertEquals("<div>...</div>", resultMap.get("search_suggestions"));

    // The live v2 wire delivers `result` as a List of suggestion chips, not a single Map.
    var listJson =
        """
        {
          "id":"int_456",
          "status":"completed",
          "steps":[{"type":"google_search_result","call_id":"gs_1",
            "result":[{"search_suggestions":"<div>chip1</div>"},
                     {"search_suggestions":"<div>chip2</div>"}]}]
        }
        """;
    var listResponse = objectMapper.readValue(listJson, InteractionResponse.class);
    @SuppressWarnings("unchecked")
    var chips = (List<Map<String, Object>>) listResponse.steps().getFirst().result();
    assertEquals(2, chips.size());
    assertEquals("<div>chip1</div>", chips.get(0).get("search_suggestions"));

    var modelStep = response.steps().get(2);
    assertTrue(modelStep.isModelOutput());
    assertTrue(modelStep.content().getFirst().hasAnnotations());
  }

  @Test
  void deserializeFailedInteractionStatus() throws Exception {
    var json =
        """
        {
          "id": "int_x",
          "status": "failed",
          "steps": []
        }
        """;
    var response = objectMapper.readValue(json, InteractionResponse.class);
    assertTrue(response.isFailed());
    assertFalse(response.isCompleted());
    assertFalse(response.requiresAction());
  }

  @Test
  void deserializeStepFlags() throws Exception {
    var userStep =
        objectMapper.readValue(
            "{\"type\":\"user_input\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}",
            Step.class);
    assertTrue(userStep.isUserInput());
    assertFalse(userStep.isModelOutput());
    assertFalse(userStep.isThought());
    assertFalse(userStep.isFunctionCall());
    assertFalse(userStep.isGoogleSearchCall());
    assertFalse(userStep.isGoogleSearchResult());
    assertTrue(userStep.hasContent());
    assertFalse(userStep.hasSummary());

    var bareThought = objectMapper.readValue("{\"type\":\"thought\"}", Step.class);
    assertFalse(bareThought.hasSummary());
    assertFalse(bareThought.hasContent());
  }

  @Test
  void toolChoiceConfigFactoriesCoverEveryMode() throws Exception {
    var auto = ToolChoiceConfig.auto();
    assertEquals("auto", auto.mode());
    assertNull(auto.allowedTools());
    var any = ToolChoiceConfig.any();
    assertEquals("any", any.mode());
    assertNull(any.allowedTools());
    var none = ToolChoiceConfig.none();
    assertEquals("none", none.mode());
    assertNull(none.allowedTools());
    var validated = ToolChoiceConfig.validated(java.util.Set.of("a", "b"));
    assertEquals("validated", validated.mode());
    assertEquals(java.util.Set.of("a", "b"), validated.allowedTools());

    var validatedJson = objectMapper.writeValueAsString(validated);
    assertTrue(validatedJson.contains("\"mode\":\"validated\""));
    assertTrue(validatedJson.contains("\"allowed_tools\""));
  }

  @Test
  void interactionGenerationConfigBuilderCoversEverySetter() throws Exception {
    var cfg =
        InteractionGenerationConfig.newBuilder()
            .withTemperature(0.5)
            .withMaxOutputTokens(64)
            .withTopP(0.9)
            .withTopK(40)
            .withStopSequences(List.of("STOP"))
            .withSeed(42L)
            .withThinkingLevel("medium")
            .build();
    assertEquals(0.5, cfg.temperature());
    assertEquals(64, cfg.maxOutputTokens());
    assertEquals(0.9, cfg.topP());
    assertEquals(40, cfg.topK());
    assertEquals(List.of("STOP"), cfg.stopSequences());
    assertEquals(42L, cfg.seed());
    assertEquals("medium", cfg.thinkingLevel());

    var json = objectMapper.writeValueAsString(cfg);
    assertTrue(json.contains("\"max_output_tokens\":64"));
    assertTrue(json.contains("\"top_p\":0.9"));
    assertTrue(json.contains("\"top_k\":40"));
    assertTrue(json.contains("\"stop_sequences\""));
    assertTrue(json.contains("\"seed\":42"));
    assertTrue(json.contains("\"thinking_level\":\"medium\""));
  }

  @Test
  void interactionRequestBuilderCoversEverySetter() throws Exception {
    var gen = InteractionGenerationConfig.newBuilder().withMaxOutputTokens(16).build();
    var request =
        InteractionRequest.newBuilder()
            .withModel("gemini-3-flash-preview")
            .withInput(List.of(Turn.user("hi")))
            .withTools(List.of(ToolDefinition.googleSearch()))
            .withToolChoice(ToolChoiceConfig.auto())
            .withGenerationConfig(gen)
            .withResponseFormat(ResponseFormat.text())
            .withStream(true)
            .build();

    assertEquals("gemini-3-flash-preview", request.model());
    assertEquals(1, request.input().size());
    assertEquals(1, request.tools().size());
    assertEquals("auto", request.toolChoice().mode());
    assertEquals(16, request.generationConfig().maxOutputTokens());
    assertEquals("text", request.responseFormat().type());
    assertTrue(request.stream());
  }

  @Test
  void contentItemAnnotationHelperRejectsEmptyList() {
    var item = new ContentItem("text", "x", null, null, null, null, null, null, null, null, null);
    assertFalse(item.hasAnnotations());
    var withEmpty =
        new ContentItem("text", "x", null, null, null, null, null, null, null, null, List.of());
    assertFalse(withEmpty.hasAnnotations());
  }

  @Test
  void contentItemTypePredicatesCoverEveryShape() {
    assertTrue(ContentItem.text("x").hasTypeText());
    assertFalse(ContentItem.text("x").hasTypeFunctionCall());
    assertFalse(ContentItem.text("x").hasTypeFunctionResult());

    var fc = ContentItem.functionCall("tool", Map.of(), "id");
    assertTrue(fc.hasTypeFunctionCall());
    assertFalse(fc.hasTypeText());
    assertFalse(fc.hasTypeFunctionResult());

    var fr = ContentItem.functionResult("tool", "id", "ok");
    assertTrue(fr.hasTypeFunctionResult());
    assertFalse(fr.hasTypeFunctionCall());
    assertFalse(fr.hasTypeText());
  }

  @Test
  void stepHasSummaryHandlesEmptyAndPopulatedLists() {
    var emptySummary = new Step("thought", null, List.of(), null, null, null, null, null, null);
    assertFalse(emptySummary.hasSummary());

    var populatedSummary =
        new Step(
            "thought",
            null,
            List.of(ContentItem.text("inner")),
            "sig",
            null,
            null,
            null,
            null,
            null);
    assertTrue(populatedSummary.hasSummary());
  }

  @Test
  void stepHasContentHandlesEmptyList() {
    var step = new Step("model_output", List.of(), null, null, null, null, null, null, null);
    assertFalse(step.hasContent());
  }

  @Test
  void stepFlagsAreMutuallyExclusiveForFunctionCallAndSearch() {
    var fc = new Step("function_call", null, null, null, "id1", "name1", Map.of(), null, null);
    assertTrue(fc.isFunctionCall());
    assertFalse(fc.isModelOutput());
    assertFalse(fc.isGoogleSearchCall());

    var search =
        new Step("google_search_call", null, null, "sig", "gs_1", null, Map.of(), null, null);
    assertTrue(search.isGoogleSearchCall());
    assertFalse(search.isFunctionCall());

    var result =
        new Step(
            "google_search_result", null, null, "sig", null, null, null, "gs_1", Map.of("a", "b"));
    assertTrue(result.isGoogleSearchResult());
    assertFalse(result.isGoogleSearchCall());
  }

  @Test
  void streamingEventDiscriminatorsCoverNewEventTypes() throws Exception {
    var created =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.created\",\"interaction\":{\"id\":\"int_z\"}}",
            StreamingEvent.class);
    assertTrue(created.isInteractionCreated());
    assertFalse(created.isInteractionCompleted());

    var inProgress =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.in_progress\",\"interaction_id\":\"int_z\"}",
            StreamingEvent.class);
    assertTrue(inProgress.isInteractionInProgress());
    assertFalse(inProgress.isInteractionStatusUpdate());
    assertEquals("int_z", inProgress.interactionId());

    var requires =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.requires_action\",\"interaction_id\":\"int_z\"}",
            StreamingEvent.class);
    assertTrue(requires.isInteractionRequiresAction());

    var statusUpdate =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.status_update\","
                + "\"interaction_id\":\"int_z\",\"status\":\"in_progress\"}",
            StreamingEvent.class);
    assertTrue(statusUpdate.isInteractionStatusUpdate());
    assertFalse(statusUpdate.isInteractionInProgress());
    assertEquals("int_z", statusUpdate.interactionId());
    assertEquals("in_progress", statusUpdate.status());

    var completed =
        objectMapper.readValue(
            "{\"event_type\":\"interaction.completed\","
                + "\"interaction\":{\"id\":\"int_z\",\"status\":\"completed\"}}",
            StreamingEvent.class);
    assertTrue(completed.isInteractionCompleted());

    var start =
        objectMapper.readValue(
            "{\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"model_output\"}}",
            StreamingEvent.class);
    assertTrue(start.isStepStart());
    assertEquals(0, start.index());
    assertNotNull(start.step());

    var deltaText =
        objectMapper.readValue(
            "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text\",\"text\":\"hi\"}}",
            StreamingEvent.class);
    assertTrue(deltaText.isStepDelta());
    assertNotNull(deltaText.delta());
    assertNull(deltaText.argumentsDelta());

    var deltaArgs =
        objectMapper.readValue(
            "{\"event_type\":\"step.delta\",\"index\":1,\"arguments_delta\":\"{\\\"a\\\":1\"}",
            StreamingEvent.class);
    assertTrue(deltaArgs.isStepDelta());
    assertEquals("{\"a\":1", deltaArgs.argumentsDelta());

    var stop =
        objectMapper.readValue(
            "{\"event_type\":\"step.stop\",\"index\":0,\"status\":\"done\"}", StreamingEvent.class);
    assertTrue(stop.isStepStop());
    assertEquals("done", stop.status());
  }
}
