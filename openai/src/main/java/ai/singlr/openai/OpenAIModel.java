/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ThinkingLevel;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.model.ToolChoice;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.openai.api.ApiStreamEvent;
import ai.singlr.openai.api.InputItem;
import ai.singlr.openai.api.ResponsesRequest;
import ai.singlr.openai.api.TextFormatConfig;
import ai.singlr.openai.api.ToolDefinition;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI model implementation using the Responses API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link OpenAIException}.
 */
public class OpenAIModel implements Model {

  private static final String PROVIDER_NAME = "openai";
  private static final String BASE_URL = "https://api.openai.com/v1/responses";
  private static final int DEFAULT_MAX_TOKENS = 4096;

  static final String REASONING_KEY = "openai.reasoning";

  private final OpenAIModelId modelId;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  OpenAIModel(OpenAIModelId modelId, ModelConfig config) {
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
  public int contextWindow() {
    return modelId.contextWindow();
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, null);
    return streamAndDrain(request);
  }

  @Override
  public <T> Response<T> chat(
      List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
    var request = buildRequest(messages, tools, outputSchema.schema().toMap());
    var response = streamAndDrain(request);
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
  }

  @Override
  public CloseableIterator<StreamEvent> chatStream(List<Message> messages, List<Tool> tools) {
    var request = buildRequest(messages, tools, null);
    try {
      return openStream(request);
    } catch (OpenAIException e) {
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error(e.getMessage(), e)).iterator());
    } catch (IOException e) {
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Failed to connect", e)).iterator());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CloseableIterator.of(
          List.of((StreamEvent) new StreamEvent.Error("Request interrupted", e)).iterator());
    }
  }

  <T> T parseStructuredContent(String content, Class<T> type) {
    if (content == null || content.isBlank()) {
      return null;
    }
    var trimmed = content.trim();
    try {
      return objectMapper.readValue(trimmed, type);
    } catch (Exception firstAttempt) {
      var stripped = stripMarkdownWrapper(trimmed);
      if (stripped.equals(trimmed)) {
        throw new OpenAIException("Failed to parse structured output: " + content, firstAttempt);
      }
      try {
        return objectMapper.readValue(stripped, type);
      } catch (Exception e) {
        throw new OpenAIException("Failed to parse structured output: " + content, e);
      }
    }
  }

  static String stripMarkdownWrapper(String json) {
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

  private StreamingIterator openStream(ResponsesRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        throw new OpenAIException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  private Response<Void> streamAndDrain(ResponsesRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (OpenAIException e) {
      throw e;
    } catch (IOException e) {
      throw new OpenAIException("Failed to communicate with OpenAI API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenAIException("Request interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Response<Void> drainToResponse(StreamingIterator iterator) {
    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(String message, Exception cause)) {
        if (cause instanceof OpenAIException oe) {
          throw oe;
        }
        throw new OpenAIException(message, cause);
      }
    }
    throw new OpenAIException("Stream ended without completion event");
  }

  ResponsesRequest buildRequest(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var inputItems = new ArrayList<InputItem>();
    String instructions = null;

    for (var message : messages) {
      switch (message.role()) {
        case SYSTEM -> instructions = appendSystemText(instructions, message.content());
        case USER ->
            inputItems.add(
                InputItem.userMessage(message.content() != null ? message.content() : ""));
        case ASSISTANT -> inputItems.addAll(convertAssistantMessage(message));
        case TOOL ->
            inputItems.add(InputItem.functionCallOutput(message.toolCallId(), message.content()));
      }
    }

    List<ToolDefinition> toolDefs = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefs =
          tools.stream()
              .map(
                  t ->
                      ToolDefinition.function(
                          t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }

    var toolChoiceValue = buildToolChoice(tools);
    var reasoningConfig = buildReasoningConfig();

    Double temperature = config.temperature();
    if (reasoningConfig != null) {
      temperature = null;
    }

    var builder =
        ResponsesRequest.newBuilder()
            .withModel(modelId.id())
            .withInput(inputItems)
            .withInstructions(instructions)
            .withStream(true)
            .withTools(toolDefs)
            .withToolChoice(toolChoiceValue)
            .withTemperature(temperature)
            .withTopP(config.topP())
            .withMaxOutputTokens(
                config.maxOutputTokens() != null ? config.maxOutputTokens() : DEFAULT_MAX_TOKENS)
            .withStop(config.stopSequences())
            .withReasoning(reasoningConfig);

    if (outputSchema != null) {
      var strictSchema = addAdditionalPropertiesFalse(outputSchema);
      var textFormat = TextFormatConfig.jsonSchema("output", strictSchema);
      builder.withText(new ResponsesRequest.TextConfig(textFormat));
    }

    return builder.build();
  }

  private static String appendSystemText(String existing, String additional) {
    if (existing == null) {
      return additional;
    }
    return existing + "\n\n" + additional;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> addAdditionalPropertiesFalse(Map<String, Object> schema) {
    var result = new HashMap<>(schema);
    if ("object".equals(result.get("type"))) {
      result.put("additionalProperties", false);
      if (result.get("properties") instanceof Map<?, ?> props) {
        var newProps = new HashMap<String, Object>();
        for (var entry : ((Map<String, Object>) props).entrySet()) {
          if (entry.getValue() instanceof Map<?, ?> nested) {
            newProps.put(
                entry.getKey(), addAdditionalPropertiesFalse((Map<String, Object>) nested));
          } else {
            newProps.put(entry.getKey(), entry.getValue());
          }
        }
        result.put("properties", newProps);
      }
    }
    if ("array".equals(result.get("type")) && result.get("items") instanceof Map<?, ?> items) {
      result.put("items", addAdditionalPropertiesFalse((Map<String, Object>) items));
    }
    return result;
  }

  List<InputItem> convertAssistantMessage(Message message) {
    var items = new ArrayList<InputItem>();

    if (message.content() != null && !message.content().isEmpty()) {
      items.add(InputItem.assistantMessage(message.content()));
    }

    if (message.hasToolCalls()) {
      for (var tc : message.toolCalls()) {
        var argsJson = serializeArguments(tc.arguments());
        items.add(InputItem.functionCall(tc.id(), tc.name(), argsJson));
      }
    }

    if (items.isEmpty()) {
      items.add(InputItem.assistantMessage(""));
    }

    return items;
  }

  private String serializeArguments(Map<String, Object> arguments) {
    if (arguments == null || arguments.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(arguments);
    } catch (Exception e) {
      throw new OpenAIException("Failed to serialize tool call arguments", e);
    }
  }

  private Object buildToolChoice(List<Tool> tools) {
    if (config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> "auto";
      case ToolChoice.Any a -> "required";
      case ToolChoice.None n -> "none";
      case ToolChoice.Required r -> {
        var name = r.allowedTools().iterator().next();
        yield Map.of("type", "function", "name", name);
      }
    };
  }

  private ResponsesRequest.ReasoningConfig buildReasoningConfig() {
    if (config.thinkingLevel() == null || config.thinkingLevel() == ThinkingLevel.NONE) {
      return null;
    }

    var effort =
        switch (config.thinkingLevel()) {
          case NONE -> null;
          case MINIMAL, LOW -> "low";
          case MEDIUM -> "medium";
          case HIGH -> "high";
        };

    return ResponsesRequest.ReasoningConfig.of(effort);
  }

  String serializeRequest(ResponsesRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new OpenAIException("Failed to serialize request", e);
    }
  }

  HttpRequest buildHttpRequest(String jsonBody) {
    return HttpRequest.newBuilder()
        .uri(URI.create(BASE_URL))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + config.apiKey())
        .timeout(config.responseTimeout())
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();
  }

  static FinishReason mapStatus(String status) {
    if (status == null) {
      return FinishReason.STOP;
    }
    return switch (status) {
      case "completed" -> FinishReason.STOP;
      case "incomplete" -> FinishReason.LENGTH;
      case "failed" -> FinishReason.ERROR;
      default -> FinishReason.STOP;
    };
  }

  static class StreamingIterator implements CloseableIterator<StreamEvent> {
    private final InputStream rawStream;
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final Duration streamIdleTimeout;
    private final ExecutorService readExecutor;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final Map<String, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
    private final StringBuilder reasoningBuilder = new StringBuilder();
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private String responseStatus = null;

    StreamingIterator(
        HttpResponse<InputStream> response, ObjectMapper objectMapper, Duration streamIdleTimeout) {
      this.rawStream = response.body();
      this.reader =
          new BufferedReader(new InputStreamReader(this.rawStream, StandardCharsets.UTF_8));
      this.objectMapper = objectMapper;
      this.streamIdleTimeout = streamIdleTimeout;
      this.readExecutor = Executors.newVirtualThreadPerTaskExecutor();
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

    private String readLineWithTimeout() throws IOException {
      try {
        return CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return reader.readLine();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                },
                readExecutor)
            .get(streamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new OpenAIException(
            "Stream idle timeout: no data received for " + streamIdleTimeout.toSeconds() + "s");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof UncheckedIOException uio) {
          throw uio.getCause();
        }
        throw new IOException("Stream read failed", e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Stream read interrupted", e);
      }
    }

    private StreamEvent readNextEvent() {
      try {
        String line;
        while ((line = readLineWithTimeout()) != null) {
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
        close();
        return buildDoneEvent();
      } catch (OpenAIException e) {
        done = true;
        close();
        return new StreamEvent.Error(e.getMessage(), e);
      } catch (IOException e) {
        done = true;
        close();
        return new StreamEvent.Error("Stream read error", e);
      }
    }

    @SuppressWarnings("unchecked")
    private StreamEvent parseStreamEvent(String json) {
      try {
        var event = objectMapper.readValue(json, ApiStreamEvent.class);

        if (event.hasTypeResponseOutputTextDelta()) {
          if (event.delta() != null) {
            contentBuilder.append(event.delta());
            return new StreamEvent.TextDelta(event.delta());
          }
          return null;
        }

        if (event.hasTypeResponseOutputItemAdded()) {
          if (event.item() != null && event.item().hasTypeFunctionCall()) {
            toolCallAccumulators.put(
                event.item().id(),
                new ToolCallAccumulator(
                    event.item().callId(), event.item().name(), new StringBuilder()));
            return new StreamEvent.ToolCallStart(event.item().callId(), event.item().name());
          }
          return null;
        }

        if (event.hasTypeFunctionCallArgumentsDelta()) {
          if (event.delta() != null && event.itemId() != null) {
            var accumulator = toolCallAccumulators.get(event.itemId());
            if (accumulator != null) {
              accumulator.jsonBuilder().append(event.delta());
            }
          }
          return null;
        }

        if (event.hasTypeFunctionCallArgumentsDone()) {
          if (event.itemId() != null) {
            var accumulator = toolCallAccumulators.remove(event.itemId());
            if (accumulator != null) {
              var jsonStr = accumulator.jsonBuilder().toString();
              Map<String, Object> arguments = Map.of();
              if (!jsonStr.isEmpty()) {
                try {
                  arguments = objectMapper.readValue(jsonStr, Map.class);
                } catch (Exception e) {
                  arguments = Map.of("_raw", jsonStr);
                }
              }
              var tc =
                  ToolCall.newBuilder()
                      .withId(accumulator.callId())
                      .withName(accumulator.name())
                      .withArguments(arguments)
                      .build();
              toolCalls.add(tc);
              return new StreamEvent.ToolCallComplete(tc);
            }
          }
          return null;
        }

        if (event.hasTypeResponseCompleted()) {
          if (event.response() != null) {
            responseStatus = event.response().status();
            if (event.response().usage() != null) {
              var usage = event.response().usage();
              if (usage.inputTokens() != null) {
                inputTokens = usage.inputTokens();
              }
              if (usage.outputTokens() != null) {
                outputTokens = usage.outputTokens();
              }
            }
          }
          done = true;
          close();
          return buildDoneEvent();
        }

        if (event.hasTypeResponseFailed()) {
          done = true;
          close();
          return new StreamEvent.Error("API response failed: " + json, null);
        }

        if (event.hasTypeError()) {
          return new StreamEvent.Error("API stream error: " + json, null);
        }

        if (event.hasTypeReasoningSummaryTextDelta()) {
          if (event.text() != null) {
            reasoningBuilder.append(event.text());
          }
          return null;
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent buildDoneEvent() {
      var content = contentBuilder.toString();
      var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);

      var finishReason = mapStatus(responseStatus);
      if (!calls.isEmpty() && finishReason != FinishReason.TOOL_CALLS) {
        finishReason = FinishReason.TOOL_CALLS;
      }

      Response.Usage usage = null;
      if (inputTokens > 0 || outputTokens > 0) {
        usage = Response.Usage.of(inputTokens, outputTokens);
      }

      String thinking = reasoningBuilder.isEmpty() ? null : reasoningBuilder.toString();

      var metadata = new HashMap<String, String>();
      if (thinking != null) {
        metadata.put(REASONING_KEY, thinking);
      }

      var response =
          Response.newBuilder()
              .withContent(content)
              .withToolCalls(calls)
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinking)
              .withMetadata(metadata.isEmpty() ? Map.of() : Map.copyOf(metadata))
              .build();

      return new StreamEvent.Done(response);
    }

    @Override
    public void close() {
      done = true;
      readExecutor.shutdownNow();
      try {
        rawStream.close();
      } catch (IOException ignored) {
      }
      try {
        reader.close();
      } catch (IOException ignored) {
      }
    }

    private record ToolCallAccumulator(String callId, String name, StringBuilder jsonBuilder) {}
  }
}
