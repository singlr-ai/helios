/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    assertFalse(json.contains("name"));
    assertFalse(json.contains("arguments"));
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
  void deserializeOutputItemWithAnnotations() throws Exception {
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

    var output = objectMapper.readValue(json, OutputItem.class);

    assertEquals("text", output.type());
    assertTrue(output.isText());
    assertTrue(output.hasAnnotations());
    assertEquals(1, output.annotations().size());

    var annotation = output.annotations().getFirst();
    assertEquals("url_citation", annotation.type());
    assertEquals("https://example.com", annotation.url());
    assertEquals("Example Source", annotation.title());
    assertEquals(0, annotation.startIndex());
    assertEquals(25, annotation.endIndex());
  }

  @Test
  void deserializeOutputItemWithoutAnnotations() throws Exception {
    var json =
        """
        {
          "type": "text",
          "text": "Hello world"
        }
        """;

    var output = objectMapper.readValue(json, OutputItem.class);

    assertEquals("text", output.type());
    assertFalse(output.hasAnnotations());
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
  void deserializeInteractionResponseWithAnnotations() throws Exception {
    var json =
        """
        {
          "id": "int_123",
          "model": "gemini-3-flash-preview",
          "status": "completed",
          "outputs": [
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
        """;

    var response = objectMapper.readValue(json, InteractionResponse.class);

    assertNotNull(response.outputs());
    assertEquals(1, response.outputs().size());

    var output = response.outputs().getFirst();
    assertTrue(output.hasAnnotations());
    assertEquals("url_citation", output.annotations().getFirst().type());
  }
}
