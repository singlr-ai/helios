/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.gemini.api.ContentItem;
import ai.singlr.gemini.api.InteractionGenerationConfig;
import ai.singlr.gemini.api.InteractionRequest;
import ai.singlr.gemini.api.InteractionResponse;
import ai.singlr.gemini.api.InteractionUsage;
import ai.singlr.gemini.api.OutputItem;
import ai.singlr.gemini.api.StreamingEvent;
import ai.singlr.gemini.api.ToolChoiceConfig;
import ai.singlr.gemini.api.ToolDefinition;
import ai.singlr.gemini.api.Turn;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini model implementation using the Interactions API.
 *
 * <p>Supports synchronous and streaming responses, function calling, and thinking/reasoning for
 * Gemini 3+ models.
 */
public class GeminiModel implements Model {

  private static final String PROVIDER_NAME = "gemini";
  private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
  static final String THOUGHT_SIGNATURES_KEY = "gemini.thoughtSignatures";
  static final String SIGNATURE_DELIMITER = "\u001E";

  private final GeminiModelId modelId;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  GeminiModel(GeminiModelId modelId, ModelConfig config) {
    if (modelId == null) {
      throw new IllegalArgumentException("modelId is required");
    }
    if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
      throw new IllegalArgumentException("config with valid apiKey is required");
    }
    this.modelId = modelId;
    this.config = config;
    this.httpClient = HttpClientFactory.create(config);
    this.objectMapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  @Override
  public String id() {
    return modelId.id();
  }

  @Override
  public String provider() {
    return PROVIDER_NAME;
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, false, null);
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody, false);

    try {
      var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      return parseResponse(httpResponse);
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with Gemini API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Request interrupted", e);
    }
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = buildRequest(messages, tools, false, outputSchema.schema().toMap());
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody, false);

    try {
      var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      var response = parseResponse(httpResponse);
      var parsed = parseStructuredContent(response.content(), outputSchema.type());

      return Response.<T>newBuilder(outputSchema.type())
          .withContent(response.content())
          .withParsed(parsed)
          .withToolCalls(response.toolCalls())
          .withFinishReason(response.finishReason())
          .withUsage(response.usage())
          .withThinking(response.thinking())
          .withCitations(response.citations())
          .withMetadata(response.metadata())
          .build();
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with Gemini API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Request interrupted", e);
    }
  }

  private <T> T parseStructuredContent(String content, Class<T> type) {
    if (content == null || content.isBlank()) {
      return null;
    }
    var trimmed = content.trim();
    try {
      return objectMapper.readValue(trimmed, type);
    } catch (Exception firstAttempt) {
      var stripped = stripMarkdownWrapper(trimmed);
      if (stripped.equals(trimmed)) {
        throw new GeminiException("Failed to parse structured output: " + content, firstAttempt);
      }
      try {
        return objectMapper.readValue(stripped, type);
      } catch (Exception e) {
        throw new GeminiException("Failed to parse structured output: " + content, e);
      }
    }
  }

  private static String stripMarkdownWrapper(String json) {
    var result = json;
    if (result.startsWith("```json")) {
      result = result.substring(7);
    } else if (result.startsWith("```")) {
      result = result.substring(3);
    }
    if (result.endsWith("```")) {
      result = result.substring(0, result.length() - 3);
    }
    return result.trim();
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, true, null);
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody, true);

    try {
      var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      return new StreamingIterator(httpResponse, objectMapper);
    } catch (IOException e) {
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Failed to connect", e)).iterator());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Request interrupted", e)).iterator());
    }
  }

  private InteractionRequest buildRequest(
      List<Message> messages,
      List<Tool> tools,
      boolean stream,
      Map<String, Object> responseFormat) {
    var input = new ArrayList<Turn>();
    String systemInstruction = null;

    for (var message : messages) {
      if (message.role() == Role.SYSTEM) {
        systemInstruction = message.content();
      } else {
        input.add(convertMessage(message));
      }
    }

    List<ToolDefinition> toolDefinitions = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefinitions =
          tools.stream()
              .map(
                  t ->
                      ToolDefinition.function(
                          t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }

    var generationConfig = buildGenerationConfig();
    var toolChoice = buildToolChoice();

    return InteractionRequest.newBuilder()
        .withModel(modelId.id())
        .withInput(input)
        .withSystemInstruction(systemInstruction)
        .withTools(toolDefinitions)
        .withToolChoice(toolChoice)
        .withGenerationConfig(generationConfig)
        .withResponseFormat(responseFormat)
        .withStream(stream ? true : null)
        .build();
  }

  private Turn convertMessage(Message message) {
    return switch (message.role()) {
      case USER -> Turn.user(message.content());
      case ASSISTANT -> {
        if (message.hasToolCalls()) {
          var items = new ArrayList<ContentItem>();
          var signatures = message.metadata().getOrDefault(THOUGHT_SIGNATURES_KEY, "");
          if (!signatures.isEmpty()) {
            for (var sig : signatures.split(SIGNATURE_DELIMITER)) {
              items.add(ContentItem.thought(sig));
            }
          }
          for (var tc : message.toolCalls()) {
            items.add(ContentItem.functionCall(tc.name(), tc.arguments(), tc.id()));
          }
          yield Turn.model(items);
        }
        yield Turn.model(message.content());
      }
      case TOOL ->
          Turn.user(
              List.of(
                  ContentItem.functionResult(
                      message.toolName(), message.toolCallId(), message.content())));
      case SYSTEM -> throw new IllegalStateException("System messages handled separately");
    };
  }

  private InteractionGenerationConfig buildGenerationConfig() {
    if (config == null) {
      return null;
    }

    var hasGenerationParams =
        config.temperature() != null
            || config.topP() != null
            || config.maxOutputTokens() != null
            || config.stopSequences() != null
            || config.seed() != null
            || (config.thinkingLevel() != null && config.thinkingLevel() != ThinkingLevel.NONE);

    if (!hasGenerationParams) {
      return null;
    }

    var builder = InteractionGenerationConfig.newBuilder();

    if (config.temperature() != null) {
      builder.withTemperature(config.temperature());
    }
    if (config.topP() != null) {
      builder.withTopP(config.topP());
    }
    if (config.maxOutputTokens() != null) {
      builder.withMaxOutputTokens(config.maxOutputTokens());
    }
    if (config.stopSequences() != null) {
      builder.withStopSequences(config.stopSequences());
    }
    if (config.seed() != null) {
      builder.withSeed(config.seed());
    }
    if (config.thinkingLevel() != null && config.thinkingLevel() != ThinkingLevel.NONE) {
      var thinkingLevel =
          switch (config.thinkingLevel()) {
            case NONE -> "none";
            case MINIMAL, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
          };
      builder.withThinkingLevel(thinkingLevel);
    }

    return builder.build();
  }

  private ToolChoiceConfig buildToolChoice() {
    if (config == null || config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> ToolChoiceConfig.auto();
      case ToolChoice.Any a -> ToolChoiceConfig.any();
      case ToolChoice.None n -> ToolChoiceConfig.none();
      case ToolChoice.Required r -> ToolChoiceConfig.validated(r.allowedTools());
    };
  }

  private String serializeRequest(InteractionRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new GeminiException("Failed to serialize request", e);
    }
  }

  private HttpRequest buildHttpRequest(String jsonBody, boolean streaming) {
    var endpoint = streaming ? "/interactions?alt=sse" : "/interactions";
    var uri = URI.create(BASE_URL + endpoint);

    var builder =
        HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", config.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

    if (config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }

    return builder.build();
  }

  private Response<Void> parseResponse(HttpResponse<String> httpResponse) {
    if (httpResponse.statusCode() != 200) {
      throw new GeminiException(
          "API error (status " + httpResponse.statusCode() + "): " + httpResponse.body(),
          httpResponse.statusCode());
    }

    try {
      var interactionResponse =
          objectMapper.readValue(httpResponse.body(), InteractionResponse.class);
      return convertResponse(interactionResponse);
    } catch (GeminiException e) {
      throw e;
    } catch (Exception e) {
      throw new GeminiException("Failed to parse response", e);
    }
  }

  private Response<Void> convertResponse(InteractionResponse interaction) {
    if (interaction.isFailed()) {
      return Response.newBuilder().withContent("").withFinishReason(FinishReason.ERROR).build();
    }

    var content = extractContent(interaction.outputs());
    var toolCalls = extractToolCalls(interaction.outputs());
    var thinking = extractThinking(interaction.outputs());
    var thoughtSignatures = extractThoughtSignatures(interaction.outputs());
    var finishReason = determineFinishReason(interaction, toolCalls);
    var usage = convertUsage(interaction.usage());

    var metadata = new HashMap<String, String>();
    if (!thoughtSignatures.isEmpty()) {
      metadata.put(THOUGHT_SIGNATURES_KEY, String.join(SIGNATURE_DELIMITER, thoughtSignatures));
    }

    return Response.newBuilder()
        .withContent(content)
        .withToolCalls(toolCalls)
        .withFinishReason(finishReason)
        .withUsage(usage)
        .withThinking(thinking)
        .withMetadata(metadata.isEmpty() ? Map.of() : Map.copyOf(metadata))
        .build();
  }

  private String extractContent(List<OutputItem> outputs) {
    if (outputs == null) {
      return "";
    }
    return outputs.stream()
        .filter(OutputItem::isText)
        .map(OutputItem::text)
        .reduce("", (a, b) -> a + b);
  }

  private List<ToolCall> extractToolCalls(List<OutputItem> outputs) {
    if (outputs == null) {
      return List.of();
    }
    return outputs.stream()
        .filter(OutputItem::isFunctionCall)
        .map(
            item ->
                ToolCall.newBuilder()
                    .withId(item.id())
                    .withName(item.name())
                    .withArguments(item.arguments() != null ? item.arguments() : Map.of())
                    .build())
        .toList();
  }

  private String extractThinking(List<OutputItem> outputs) {
    if (outputs == null) {
      return null;
    }
    var thoughts = outputs.stream().filter(OutputItem::isThought).map(OutputItem::summary).toList();
    return thoughts.isEmpty() ? null : String.join("\n", thoughts);
  }

  private List<String> extractThoughtSignatures(List<OutputItem> outputs) {
    if (outputs == null) {
      return List.of();
    }
    return outputs.stream()
        .filter(OutputItem::isThought)
        .map(OutputItem::signature)
        .filter(sig -> sig != null && !sig.isEmpty())
        .toList();
  }

  private FinishReason determineFinishReason(
      InteractionResponse interaction, List<ToolCall> toolCalls) {
    if (!toolCalls.isEmpty()) {
      return FinishReason.TOOL_CALLS;
    }
    if (interaction.isFailed()) {
      return FinishReason.ERROR;
    }
    return FinishReason.STOP;
  }

  private Response.Usage convertUsage(InteractionUsage usage) {
    if (usage == null) {
      return null;
    }
    return Response.Usage.of(
        usage.inputTokens() != null ? usage.inputTokens() : 0,
        usage.outputTokens() != null ? usage.outputTokens() : 0);
  }

  private static class StreamingIterator implements CloseableIterator<StreamEvent> {
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private String thinkingContent = null;
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private InteractionUsage lastUsage = null;
    private InteractionResponse completeInteraction = null;

    StreamingIterator(HttpResponse<java.io.InputStream> response, ObjectMapper objectMapper) {
      this.reader = new BufferedReader(new InputStreamReader(response.body()));
      this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasNext() {
      if (done) {
        return false;
      }
      if (nextEvent != null) {
        return true;
      }
      nextEvent = readNextEvent();
      return nextEvent != null;
    }

    @Override
    public StreamEvent next() {
      if (nextEvent == null) {
        nextEvent = readNextEvent();
      }
      var event = nextEvent;
      nextEvent = null;
      return event;
    }

    private StreamEvent readNextEvent() {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("data: ")) {
            var json = line.substring(6).trim();
            if (json.isEmpty() || json.equals("[DONE]")) {
              continue;
            }
            var event = parseStreamEvent(json);
            if (event != null) {
              return event;
            }
          }
        }
        done = true;
        closeReader();
        return buildDoneEvent();
      } catch (IOException e) {
        done = true;
        closeReader();
        return new StreamEvent.Error("Stream read error", e);
      }
    }

    private StreamEvent parseStreamEvent(String json) {
      try {
        var streamingEvent = objectMapper.readValue(json, StreamingEvent.class);

        if (streamingEvent.isComplete()) {
          completeInteraction = streamingEvent.interaction();
          if (completeInteraction != null && completeInteraction.usage() != null) {
            lastUsage = completeInteraction.usage();
          }
          return null;
        }

        if (streamingEvent.isContentDelta() && streamingEvent.delta() != null) {
          var delta = streamingEvent.delta();

          if (delta.isText()) {
            contentBuilder.append(delta.text());
            return new StreamEvent.TextDelta(delta.text());
          }

          if (delta.isFunctionCall()) {
            var tc =
                ToolCall.newBuilder()
                    .withId(delta.id())
                    .withName(delta.name())
                    .withArguments(delta.arguments() != null ? delta.arguments() : Map.of())
                    .build();
            toolCalls.add(tc);
            return new StreamEvent.ToolCallComplete(tc);
          }

          if (delta.isThought()) {
            thinkingContent =
                (thinkingContent == null ? "" : thinkingContent + "\n") + delta.summary();
          }
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent buildDoneEvent() {
      var finishReason = FinishReason.STOP;
      if (!toolCalls.isEmpty()) {
        finishReason = FinishReason.TOOL_CALLS;
      } else if (completeInteraction != null && completeInteraction.isFailed()) {
        finishReason = FinishReason.ERROR;
      }

      Response.Usage usage = null;
      if (lastUsage != null) {
        usage =
            Response.Usage.of(
                lastUsage.inputTokens() != null ? lastUsage.inputTokens() : 0,
                lastUsage.outputTokens() != null ? lastUsage.outputTokens() : 0);
      }

      var response =
          Response.newBuilder()
              .withContent(contentBuilder.toString())
              .withToolCalls(toolCalls.isEmpty() ? List.of() : List.copyOf(toolCalls))
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinkingContent)
              .build();

      return new StreamEvent.Done(response);
    }

    @Override
    public void close() {
      done = true;
      closeReader();
    }

    private void closeReader() {
      try {
        reader.close();
      } catch (IOException ignored) {
      }
    }
  }
}
