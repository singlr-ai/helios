/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.anthropic.api.ApiStreamEvent;
import ai.singlr.anthropic.api.ContentBlock;
import ai.singlr.anthropic.api.ContentDelta;
import ai.singlr.anthropic.api.MessagesRequest;
import ai.singlr.anthropic.api.ThinkingConfig;
import ai.singlr.anthropic.api.ToolChoiceConfig;
import ai.singlr.anthropic.api.ToolDefinition;
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
 * Anthropic Claude model implementation using the Messages API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link AnthropicException}.
 */
public class AnthropicModel implements Model {

  private static final String PROVIDER_NAME = "anthropic";
  private static final String BASE_URL = "https://api.anthropic.com/v1/messages";
  private static final String API_VERSION = "2023-06-01";
  private static final int DEFAULT_MAX_TOKENS = 4096;

  static final String THINKING_KEY = "anthropic.thinking";
  static final String THINKING_SIGNATURE_KEY = "anthropic.thinkingSignature";

  private final AnthropicModelId modelId;
  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  AnthropicModel(AnthropicModelId modelId, ModelConfig config) {
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
    } catch (AnthropicException e) {
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
        throw new AnthropicException("Failed to parse structured output: " + content, firstAttempt);
      }
      try {
        return objectMapper.readValue(stripped, type);
      } catch (Exception e) {
        throw new AnthropicException("Failed to parse structured output: " + content, e);
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

  private StreamingIterator openStream(MessagesRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        throw new AnthropicException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  private Response<Void> streamAndDrain(MessagesRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (AnthropicException e) {
      throw e;
    } catch (IOException e) {
      throw new AnthropicException("Failed to communicate with Anthropic API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AnthropicException("Request interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Response<Void> drainToResponse(StreamingIterator iterator) {
    while (iterator.hasNext()) {
      var event = iterator.next();
      if (event instanceof StreamEvent.Done(var response)) {
        return (Response<Void>) response;
      }
      if (event instanceof StreamEvent.Error(String message, Exception cause)) {
        if (cause instanceof AnthropicException ae) {
          throw ae;
        }
        throw new AnthropicException(message, cause);
      }
    }
    throw new AnthropicException("Stream ended without completion event");
  }

  MessagesRequest buildRequest(
      List<Message> messages, List<Tool> tools, Map<String, Object> outputSchema) {
    var apiMessages = new ArrayList<MessagesRequest.MessageEntry>();
    String systemInstruction = null;

    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      switch (message.role()) {
        case SYSTEM -> systemInstruction = appendSystemText(systemInstruction, message.content());
        case USER -> apiMessages.add(convertUserMessage(message));
        case ASSISTANT -> apiMessages.add(convertAssistantMessage(message));
        case TOOL -> {
          var toolResults = new ArrayList<ContentBlock>();
          toolResults.add(ContentBlock.toolResult(message.toolCallId(), message.content()));
          while (i + 1 < messages.size() && messages.get(i + 1).role() == Role.TOOL) {
            i++;
            var next = messages.get(i);
            toolResults.add(ContentBlock.toolResult(next.toolCallId(), next.content()));
          }
          apiMessages.add(MessagesRequest.MessageEntry.user(toolResults));
        }
      }
    }

    if (outputSchema != null) {
      var schemaJson = serializeValue(outputSchema);
      var instruction =
          "You must respond with valid JSON matching this schema:\n"
              + schemaJson
              + "\nDo not wrap the JSON in markdown code blocks. Output only the raw JSON.";
      systemInstruction = appendSystemText(systemInstruction, instruction);
    }

    List<ToolDefinition> toolDefs = null;
    if (tools != null && !tools.isEmpty()) {
      toolDefs =
          tools.stream()
              .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersAsJsonSchema()))
              .toList();
    }

    var toolChoiceConfig = buildToolChoice(tools);
    var thinkingConfig = buildThinkingConfig();

    int maxTokens =
        config.maxOutputTokens() != null ? config.maxOutputTokens() : DEFAULT_MAX_TOKENS;
    if (thinkingConfig != null && thinkingConfig.budgetTokens() != null) {
      maxTokens = Math.max(maxTokens, thinkingConfig.budgetTokens() + 1024);
    }

    Double temperature = config.temperature();
    if (thinkingConfig != null && "enabled".equals(thinkingConfig.type())) {
      temperature = null;
    }

    return MessagesRequest.newBuilder()
        .withModel(modelId.id())
        .withMaxTokens(maxTokens)
        .withMessages(apiMessages)
        .withSystem(systemInstruction)
        .withStream(true)
        .withTools(toolDefs)
        .withToolChoice(toolChoiceConfig)
        .withTemperature(temperature)
        .withTopP(config.topP())
        .withStopSequences(config.stopSequences())
        .withThinking(thinkingConfig)
        .build();
  }

  private static String appendSystemText(String existing, String additional) {
    if (existing == null) {
      return additional;
    }
    return existing + "\n\n" + additional;
  }

  private static MessagesRequest.MessageEntry convertUserMessage(Message message) {
    return MessagesRequest.MessageEntry.user(message.content() != null ? message.content() : "");
  }

  static MessagesRequest.MessageEntry convertAssistantMessage(Message message) {
    var hasThinkingSignature =
        message.metadata() != null
            && message.metadata().containsKey(THINKING_SIGNATURE_KEY)
            && !message.metadata().get(THINKING_SIGNATURE_KEY).isEmpty();

    if (!message.hasToolCalls() && !hasThinkingSignature) {
      return MessagesRequest.MessageEntry.assistant(
          message.content() != null ? message.content() : "");
    }

    var blocks = new ArrayList<ContentBlock>();

    if (hasThinkingSignature) {
      var thinkingText = message.metadata().getOrDefault(THINKING_KEY, "");
      var signature = message.metadata().get(THINKING_SIGNATURE_KEY);
      blocks.add(ContentBlock.thinking(thinkingText, signature));
    }

    if (message.content() != null && !message.content().isEmpty()) {
      blocks.add(ContentBlock.text(message.content()));
    }

    for (var tc : message.toolCalls()) {
      blocks.add(ContentBlock.toolUse(tc.id(), tc.name(), tc.arguments()));
    }

    return MessagesRequest.MessageEntry.assistant(blocks);
  }

  private ToolChoiceConfig buildToolChoice(List<Tool> tools) {
    if (config.toolChoice() == null) {
      return null;
    }

    return switch (config.toolChoice()) {
      case ToolChoice.Auto a -> ToolChoiceConfig.auto();
      case ToolChoice.Any a -> ToolChoiceConfig.any();
      case ToolChoice.None n -> null;
      case ToolChoice.Required r -> {
        if (r.allowedTools().size() > 1) {
          throw new IllegalStateException(
              "Claude tool choice supports only a single tool name, got: " + r.allowedTools());
        }
        yield ToolChoiceConfig.tool(r.allowedTools().iterator().next());
      }
    };
  }

  private ThinkingConfig buildThinkingConfig() {
    if (config.thinkingLevel() == null || config.thinkingLevel() == ThinkingLevel.NONE) {
      return null;
    }

    var budgetTokens =
        switch (config.thinkingLevel()) {
          case NONE -> 0;
          case MINIMAL -> 1024;
          case LOW -> 4096;
          case MEDIUM -> 10000;
          case HIGH -> 32000;
        };

    return ThinkingConfig.enabled(budgetTokens);
  }

  private String serializeRequest(MessagesRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (Exception e) {
      throw new AnthropicException("Failed to serialize request", e);
    }
  }

  private String serializeValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new AnthropicException("Failed to serialize value", e);
    }
  }

  private HttpRequest buildHttpRequest(String jsonBody) {
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey())
            .header("anthropic-version", API_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

    if (config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }

    return builder.build();
  }

  static FinishReason mapStopReason(String stopReason) {
    if (stopReason == null) {
      return FinishReason.STOP;
    }
    return switch (stopReason) {
      case "end_turn", "stop_sequence" -> FinishReason.STOP;
      case "tool_use" -> FinishReason.TOOL_CALLS;
      case "max_tokens" -> FinishReason.LENGTH;
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
    private final Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
    private final StringBuilder thinkingBuilder = new StringBuilder();
    private final StringBuilder signatureBuilder = new StringBuilder();
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private String stopReason = null;

    StreamingIterator(
        HttpResponse<InputStream> response, ObjectMapper objectMapper, Duration streamIdleTimeout) {
      this.rawStream = response.body();
      this.reader = new BufferedReader(new InputStreamReader(this.rawStream));
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
        throw new AnthropicException(
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
      } catch (AnthropicException e) {
        done = true;
        close();
        return new StreamEvent.Error(e.getMessage(), e);
      } catch (IOException e) {
        done = true;
        close();
        return new StreamEvent.Error("Stream read error", e);
      }
    }

    private StreamEvent parseStreamEvent(String json) {
      try {
        var event = objectMapper.readValue(json, ApiStreamEvent.class);

        if (event.hasTypeMessageStart()) {
          if (event.message() != null && event.message().usage() != null) {
            var usage = event.message().usage();
            if (usage.inputTokens() != null) {
              inputTokens = usage.inputTokens();
            }
          }
          return null;
        }

        if (event.hasTypeContentBlockStart()) {
          if (event.contentBlock() != null && event.contentBlock().hasTypeToolUse()) {
            toolCallAccumulators.put(
                event.index(),
                new ToolCallAccumulator(
                    event.contentBlock().id(), event.contentBlock().name(), new StringBuilder()));
          }
          return null;
        }

        if (event.hasTypeContentBlockDelta() && event.delta() != null) {
          return handleContentBlockDelta(event.index(), event.delta());
        }

        if (event.hasTypeContentBlockStop()) {
          return handleContentBlockStop(event.index());
        }

        if (event.hasTypeMessageDelta()) {
          if (event.delta() != null && event.delta().stopReason() != null) {
            stopReason = event.delta().stopReason();
          }
          if (event.usage() != null && event.usage().outputTokens() != null) {
            outputTokens = event.usage().outputTokens();
          }
          return null;
        }

        if (event.hasTypeMessageStop()) {
          done = true;
          close();
          return buildDoneEvent();
        }

        if (event.hasTypeError()) {
          return new StreamEvent.Error("API stream error: " + json, null);
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent handleContentBlockDelta(Integer index, ContentDelta delta) {
      if (delta.hasTypeTextDelta() && delta.text() != null) {
        contentBuilder.append(delta.text());
        return new StreamEvent.TextDelta(delta.text());
      }

      if (delta.hasTypeInputJsonDelta() && delta.partialJson() != null && index != null) {
        var accumulator = toolCallAccumulators.get(index);
        if (accumulator != null) {
          accumulator.jsonBuilder().append(delta.partialJson());
        }
        return null;
      }

      if (delta.hasTypeThinkingDelta() && delta.thinking() != null) {
        thinkingBuilder.append(delta.thinking());
        return null;
      }

      if (delta.hasTypeSignatureDelta() && delta.signature() != null) {
        signatureBuilder.append(delta.signature());
        return null;
      }

      return null;
    }

    @SuppressWarnings("unchecked")
    private StreamEvent handleContentBlockStop(Integer index) {
      if (index == null) {
        return null;
      }
      var accumulator = toolCallAccumulators.remove(index);
      if (accumulator == null) {
        return null;
      }

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
              .withId(accumulator.id())
              .withName(accumulator.name())
              .withArguments(arguments)
              .build();
      toolCalls.add(tc);
      return new StreamEvent.ToolCallComplete(tc);
    }

    private StreamEvent buildDoneEvent() {
      var content = contentBuilder.toString();
      var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);

      var finishReason = mapStopReason(stopReason);
      if (!calls.isEmpty() && finishReason != FinishReason.TOOL_CALLS) {
        finishReason = FinishReason.TOOL_CALLS;
      }

      Response.Usage usage = null;
      if (inputTokens > 0 || outputTokens > 0) {
        usage = Response.Usage.of(inputTokens, outputTokens);
      }

      String thinking = thinkingBuilder.isEmpty() ? null : thinkingBuilder.toString();
      String signature = signatureBuilder.isEmpty() ? null : signatureBuilder.toString();

      var metadata = new HashMap<String, String>();
      if (thinking != null) {
        metadata.put(THINKING_KEY, thinking);
      }
      if (signature != null) {
        metadata.put(THINKING_SIGNATURE_KEY, signature);
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

    private record ToolCallAccumulator(String id, String name, StringBuilder jsonBuilder) {}
  }
}
