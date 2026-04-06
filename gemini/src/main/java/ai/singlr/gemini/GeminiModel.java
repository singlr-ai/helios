/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.model.Citation;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini model implementation using the Interactions API.
 *
 * <p>All requests use SSE streaming internally for robust timeout handling. Synchronous {@link
 * #chat} methods stream under the hood and accumulate the response, avoiding HTTP read timeouts on
 * long-running generations. A per-line idle timeout detects stalled streams and throws a retryable
 * {@link GeminiException}.
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
    } catch (GeminiException e) {
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

  private StreamingIterator openStream(InteractionRequest request)
      throws IOException, InterruptedException {
    var jsonBody = serializeRequest(request);
    var httpRequest = buildHttpRequest(jsonBody);
    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
    if (httpResponse.statusCode() != 200) {
      try (var body = httpResponse.body()) {
        var errorBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        throw new GeminiException(
            "API error (status " + httpResponse.statusCode() + "): " + errorBody,
            httpResponse.statusCode());
      }
    }
    return new StreamingIterator(httpResponse, objectMapper, config.streamIdleTimeout());
  }

  private Response<Void> streamAndDrain(InteractionRequest request) {
    try (var iterator = openStream(request)) {
      return drainToResponse(iterator);
    } catch (GeminiException e) {
      throw e;
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with Gemini API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Request interrupted", e);
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
        if (cause instanceof GeminiException ge) {
          throw ge;
        }
        throw new GeminiException(message, cause);
      }
    }
    throw new GeminiException("Stream ended without completion event");
  }

  private InteractionRequest buildRequest(
      List<Message> messages, List<Tool> tools, Map<String, Object> responseFormat) {
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
          new ArrayList<>(
              tools.stream()
                  .map(
                      t ->
                          ToolDefinition.function(
                              t.name(), t.description(), t.parametersAsJsonSchema()))
                  .toList());
    }
    if (config.urlContext() && tools != null && !tools.isEmpty()) {
      throw new IllegalStateException("URL context cannot be combined with function calling");
    }
    if (config.googleSearch()) {
      if (toolDefinitions == null) {
        toolDefinitions = new ArrayList<>();
      }
      toolDefinitions.add(ToolDefinition.googleSearch());
    }
    if (config.urlContext()) {
      if (toolDefinitions == null) {
        toolDefinitions = new ArrayList<>();
      }
      toolDefinitions.add(ToolDefinition.urlContext());
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
        .withStream(true)
        .build();
  }

  static String interactionsContentType(String mimeType) {
    if (mimeType.startsWith("image/")) return "image";
    if (mimeType.startsWith("audio/")) return "audio";
    if (mimeType.startsWith("video/")) return "video";
    return "document";
  }

  private Turn convertMessage(Message message) {
    return switch (message.role()) {
      case USER -> {
        if (message.hasInlineFiles()) {
          var items = new ArrayList<ContentItem>();
          for (var file : message.inlineFiles()) {
            var contentType = interactionsContentType(file.mimeType());
            var base64 = Base64.getEncoder().encodeToString(file.data());
            items.add(ContentItem.inlineData(contentType, file.mimeType(), base64));
          }
          if (message.content() != null) {
            items.add(ContentItem.text(message.content()));
          }
          yield Turn.user(items);
        }
        yield Turn.user(message.content());
      }
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
    if (config.toolChoice() == null) {
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

  private HttpRequest buildHttpRequest(String jsonBody) {
    var uri = URI.create(BASE_URL + "/interactions?alt=sse");

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

  static List<Citation> extractCitations(List<OutputItem> outputs) {
    if (outputs == null) {
      return List.of();
    }
    var citations = new ArrayList<Citation>();
    for (var output : outputs) {
      if (output.isText() && output.hasAnnotations()) {
        for (var annotation : output.annotations()) {
          if ("url_citation".equals(annotation.type())) {
            citations.add(
                Citation.newBuilder()
                    .withSourceId(annotation.url())
                    .withTitle(annotation.title())
                    .withStartIndex(annotation.startIndex())
                    .withEndIndex(annotation.endIndex())
                    .build());
          }
        }
      }
    }
    return citations.isEmpty() ? List.of() : List.copyOf(citations);
  }

  static class StreamingIterator implements CloseableIterator<StreamEvent> {
    private final InputStream rawStream;
    private final BufferedReader reader;
    private final ObjectMapper objectMapper;
    private final Duration streamIdleTimeout;
    private final ExecutorService readExecutor;
    private final StringBuilder contentBuilder = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final List<String> thoughtSignatures = new ArrayList<>();
    private String thinkingContent = null;
    private StreamEvent nextEvent = null;
    private boolean done = false;
    private InteractionUsage lastUsage = null;
    private InteractionResponse completeInteraction = null;

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
      Future<String> future = readExecutor.submit((Callable<String>) () -> reader.readLine());
      try {
        return future.get(streamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new GeminiException(
            "Stream idle timeout: no data received for " + streamIdleTimeout.toSeconds() + "s");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof IOException ioe) {
          throw ioe;
        }
        throw new IOException("Stream read failed", e.getCause());
      } catch (InterruptedException e) {
        future.cancel(true);
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
      } catch (GeminiException e) {
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

          if (delta.isThought() || delta.isThoughtSignature()) {
            var thinkText = delta.summary() != null ? delta.summary() : delta.text();
            if (thinkText != null && !thinkText.isEmpty()) {
              thinkingContent = (thinkingContent == null ? "" : thinkingContent + "\n") + thinkText;
            }
            if (delta.signature() != null && !delta.signature().isEmpty()) {
              thoughtSignatures.add(delta.signature());
            }
          }
        }

        return null;
      } catch (Exception e) {
        return new StreamEvent.Error("Failed to parse stream event", e);
      }
    }

    private StreamEvent buildDoneEvent() {
      var content = contentBuilder.toString();
      var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);
      var thinking = thinkingContent;
      var signatures = new ArrayList<>(thoughtSignatures);

      if (completeInteraction != null && completeInteraction.outputs() != null) {
        var outputs = completeInteraction.outputs();
        if (content.isEmpty()) {
          content =
              outputs.stream()
                  .filter(OutputItem::isText)
                  .map(OutputItem::text)
                  .reduce("", (a, b) -> a + b);
        }
        if (calls.isEmpty()) {
          calls =
              outputs.stream()
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
        if (thinking == null) {
          var thoughts =
              outputs.stream()
                  .filter(OutputItem::isThought)
                  .map(OutputItem::summary)
                  .filter(s -> s != null && !s.isEmpty())
                  .toList();
          if (!thoughts.isEmpty()) {
            thinking = String.join("\n", thoughts);
          }
        }
        if (signatures.isEmpty()) {
          outputs.stream()
              .filter(OutputItem::isThought)
              .map(OutputItem::signature)
              .filter(sig -> sig != null && !sig.isEmpty())
              .forEach(signatures::add);
        }
      }

      var finishReason = FinishReason.STOP;
      if (!calls.isEmpty()) {
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

      var citations =
          completeInteraction != null
              ? extractCitations(completeInteraction.outputs())
              : List.<Citation>of();

      var metadata = new HashMap<String, String>();
      if (!signatures.isEmpty()) {
        metadata.put(THOUGHT_SIGNATURES_KEY, String.join(SIGNATURE_DELIMITER, signatures));
      }

      var response =
          Response.newBuilder()
              .withContent(content)
              .withToolCalls(calls)
              .withFinishReason(finishReason)
              .withUsage(usage)
              .withThinking(thinking)
              .withCitations(citations)
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
  }
}
