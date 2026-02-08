/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.schema.Description;
import ai.singlr.core.schema.Nullable;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiModelIntegrationTest {

  private static GeminiModel model;
  private static String apiKey;

  @BeforeAll
  static void setUp() {
    apiKey = System.getenv("GEMINI_API_KEY");
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    model = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);
  }

  @Test
  void simpleChat() {
    var messages = List.of(Message.user("What is 2 + 2? Reply with just the number."));

    var response = model.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.content().contains("4"));
    assertEquals(FinishReason.STOP, response.finishReason());
  }

  @Test
  void chatWithSystemMessage() {
    var messages =
        List.of(
            Message.system("You are a pirate. Always respond in pirate speak."),
            Message.user("Hello, how are you?"));

    var response = model.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(
        response.content().toLowerCase().contains("arr")
            || response.content().toLowerCase().contains("ahoy")
            || response.content().toLowerCase().contains("matey"));
  }

  @Test
  void chatWithUsageStats() {
    var messages = List.of(Message.user("Say hello"));

    var response = model.chat(messages);

    assertNotNull(response.usage());
    assertTrue(response.usage().inputTokens() > 0);
    assertTrue(response.usage().outputTokens() > 0);
    assertTrue(response.usage().totalTokens() > 0);
  }

  @Test
  void streamingChat() {
    var messages = List.of(Message.user("Count from 1 to 5, one number per line."));

    var iterator = model.chatStream(messages, List.of());

    var textDeltas = new ArrayList<String>();
    StreamEvent.Done doneEvent = null;

    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.TextDelta(String text)) {
        textDeltas.add(text);
      } else if (event instanceof StreamEvent.Done done) {
        doneEvent = done;
      }
    }

    assertFalse(textDeltas.isEmpty());
    assertNotNull(doneEvent);
    assertNotNull(doneEvent.response());

    var fullContent = String.join("", textDeltas);
    assertTrue(fullContent.contains("1"));
    assertTrue(fullContent.contains("5"));
  }

  @Test
  void chatWithToolCall() {
    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get the current weather for a location")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("location")
                    .withType(ParameterType.STRING)
                    .withDescription("The city name")
                    .withRequired(true)
                    .build())
            .withExecutor(
                args -> {
                  var location = (String) args.get("location");
                  return ToolResult.success("Weather in " + location + ": 72°F, sunny");
                })
            .build();

    var messages = List.of(Message.user("What's the weather in San Francisco?"));

    var response = model.chat(messages, List.of(weatherTool));

    assertNotNull(response);
    if (response.hasToolCalls()) {
      assertEquals(1, response.toolCalls().size());
      var toolCall = response.toolCalls().getFirst();
      assertEquals("get_weather", toolCall.name());
      assertNotNull(toolCall.arguments());
    }
  }

  @Test
  void multiTurnConversation() {
    var messages = new ArrayList<Message>();
    messages.add(Message.user("My name is Alice."));

    var response1 = model.chat(messages);
    assertNotNull(response1);

    messages.add(Message.assistant(response1.content()));
    messages.add(Message.user("What is my name?"));

    var response2 = model.chat(messages);

    assertNotNull(response2);
    assertTrue(response2.content().toLowerCase().contains("alice"));
  }

  @Test
  void modelMetadata() {
    assertEquals("gemini-3-flash-preview", model.id());
    assertEquals("gemini", model.provider());
  }

  @Test
  void chatWithThinking() {
    var config =
        ModelConfig.newBuilder().withApiKey(apiKey).withThinkingLevel(ThinkingLevel.MEDIUM).build();
    var thinkingModel = new GeminiModel(GeminiModelId.GEMINI_3_FLASH_PREVIEW, config);

    var messages =
        List.of(Message.user("What is the sum of the first 10 prime numbers? Think step by step."));

    var response = thinkingModel.chat(messages);

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.hasThinking(), "Expected thinking trace to be present");
    assertNotNull(response.thinking());
    assertFalse(response.thinking().isBlank(), "Thinking trace should not be empty");
  }

  @Test
  void fullToolRoundTrip() {
    var searchPeople =
        Tool.newBuilder()
            .withName("search_people")
            .withDescription("Finds people using semantic search")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("query")
                    .withDescription("Natural language description of who to find")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(
                args -> ToolResult.success("[{\"name\":\"Alice\",\"headline\":\"AI researcher\"}]"))
            .build();

    var messages =
        List.of(
            Message.system("You are a helpful assistant. Always use the search_people tool."),
            Message.user("Find me AI researchers"));

    var response1 = model.chat(messages, List.of(searchPeople));
    assertNotNull(response1);
    assertEquals(FinishReason.TOOL_CALLS, response1.finishReason());
    assertFalse(response1.toolCalls().isEmpty());

    var toolCall = response1.toolCalls().getFirst();
    var toolResult = searchPeople.execute(toolCall.arguments());

    var messages2 = new ArrayList<>(messages);
    messages2.add(response1.toMessage());
    messages2.add(Message.tool(toolCall.id(), toolCall.name(), toolResult.output()));

    var response2 = model.chat(messages2, List.of(searchPeople));
    assertNotNull(response2);
    assertNotNull(response2.content());
    assertEquals(FinishReason.STOP, response2.finishReason());
    assertTrue(response2.content().toLowerCase().contains("alice"));
  }

  public record Person(String name, int age, String occupation) {}

  public enum Component {
    OutputText,
    Table
  }

  @Description("Simple text block. Default component for basic responses.")
  public record OutputTextProps(@Description("The text content") String text) {}

  @Description("Use to display tabular data.")
  public record TableProps(
      @Description("Column headers") List<String> columns,
      @Description("Row data") List<List<String>> rows) {}

  public record UiResponse(
      @Description("The UI component to render") Component component,
      @Nullable @Description("Text output") OutputTextProps outputText,
      @Nullable @Description("Table output") TableProps table) {}

  @Test
  void chatWithStructuredOutput() {
    var messages =
        List.of(
            Message.user(
                "Extract the person info: John Smith is a 35-year-old software engineer."));

    var response = model.chat(messages, OutputSchema.of(Person.class));

    assertNotNull(response);
    assertNotNull(response.content());
    assertTrue(response.hasParsed(), "Expected parsed output to be present");

    var person = response.parsed();
    assertNotNull(person);
    assertEquals("John Smith", person.name());
    assertEquals(35, person.age());
    assertTrue(
        person.occupation().toLowerCase().contains("software")
            || person.occupation().toLowerCase().contains("engineer"));
  }

  @Test
  void chatWithGenerativeUi() {
    var messages =
        List.of(
            Message.system(
                "You are a UI renderer. Respond using the structured output schema. "
                    + "Choose the most appropriate component for the user's request."),
            Message.user(
                "List the first 5 prime numbers with their ordinal position"
                    + " (1st, 2nd, etc.)"));

    var response = model.chat(messages, OutputSchema.of(UiResponse.class));

    assertNotNull(response);
    assertTrue(response.hasParsed(), "Expected parsed output");

    var ui = response.parsed();
    assertEquals(Component.Table, ui.component());
    assertNotNull(ui.table(), "Expected table props");
    assertFalse(ui.table().columns().isEmpty(), "Expected columns");
    assertFalse(ui.table().rows().isEmpty(), "Expected rows");
  }
}
