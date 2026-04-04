/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static final String TEXT_DELTA =
      "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
          + "\"content_index\":0,\"delta\":\"Hello\"}\n\n";

  private static final String RESPONSE_COMPLETED =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\","
          + "\"usage\":{\"input_tokens\":25,\"output_tokens\":15,\"total_tokens\":40}}}\n\n";

  private static final String RESPONSE_COMPLETED_NO_USAGE =
      "data: {\"type\":\"response.completed\",\"response\":{"
          + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
          + "\"output\":[],\"model\":\"gpt-4o\"}}\n\n";

  private final tools.jackson.databind.ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @org.junit.jupiter.api.Test
  void textDeltaEvents() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertEquals("Hello", ((StreamEvent.TextDelta) events.get(0)).text());
      assertInstanceOf(StreamEvent.Done.class, events.get(1));

      var done = (StreamEvent.Done) events.get(1);
      assertEquals("Hello", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void toolCallFromStreaming() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDelta1 =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\"{\\\"city\\\"\"}\n\n";

    var argsDelta2 =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\":\\\"NYC\\\"}\"}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"get_weather\","
            + "\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}\n\n";

    var sse = toolItemAdded + argsDelta1 + argsDelta2 + argsDone + RESPONSE_COMPLETED;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(3, events.size());
      assertInstanceOf(StreamEvent.ToolCallStart.class, events.get(0));
      var start = (StreamEvent.ToolCallStart) events.get(0);
      assertEquals("call_1", start.callId());
      assertEquals("get_weather", start.toolName());

      assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(1));
      var tc = ((StreamEvent.ToolCallComplete) events.get(1)).toolCall();
      assertEquals("get_weather", tc.name());
      assertEquals("call_1", tc.id());
      assertEquals(Map.of("city", "NYC"), tc.arguments());

      var done = (StreamEvent.Done) events.get(2);
      assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
      assertFalse(done.response().toolCalls().isEmpty());
    }
  }

  @org.junit.jupiter.api.Test
  void reasoningSummaryCapture() {
    var reasoningDelta1 =
        "data: {\"type\":\"response.reasoning_summary_text.delta\","
            + "\"output_index\":0,\"text\":\"Let me think\"}\n\n";

    var reasoningDelta2 =
        "data: {\"type\":\"response.reasoning_summary_text.delta\","
            + "\"output_index\":0,\"text\":\" about this.\"}\n\n";

    var sse = reasoningDelta1 + reasoningDelta2 + TEXT_DELTA + RESPONSE_COMPLETED;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }

      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().thinking());
      assertEquals("Let me think about this.", done.response().thinking());
      assertTrue(done.response().metadata().containsKey(OpenAIModel.REASONING_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void usageFromCompletedEvent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(25, done.response().usage().inputTokens());
      assertEquals(15, done.response().usage().outputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse = "data: \n\ndata: [DONE]\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void nonDataLinesAreIgnored() {
    var sse = "event: ping\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void malformedJsonEmitsErrorEvent() {
    var sse = "data: {not valid json}\n\n" + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertTrue(events.size() >= 2);
      assertInstanceOf(StreamEvent.Error.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void idleTimeoutEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);

    try (var iterator =
        new OpenAIModel.StreamingIterator(
            fakeResponse(pipedIn), objectMapper, SHORT_IDLE_TIMEOUT)) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("idle timeout"));
      assertInstanceOf(OpenAIException.class, error.cause());
      assertTrue(((OpenAIException) error.cause()).isRetryable());
    }
    pipedOut.close();
  }

  @org.junit.jupiter.api.Test
  void closeIsIdempotent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void closeAfterPartialConsumption() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void multipleTextDeltas() {
    var delta2 =
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
            + "\"content_index\":0,\"delta\":\" World\"}\n\n";

    var sse = TEXT_DELTA + delta2 + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(3, events.size());
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello World", done.response().content());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyStreamProducesDoneWithEmptyContent() {
    var sse = RESPONSE_COMPLETED_NO_USAGE;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals("", done.response().content());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void noReasoningMetadataWhenNotPresent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().thinking());
      assertFalse(done.response().metadata().containsKey(OpenAIModel.REASONING_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void toolCallWithEmptyArgs() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"list_items\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"list_items\","
            + "\"arguments\":\"{}\"}\n\n";

    var sse = toolItemAdded + argsDone + RESPONSE_COMPLETED;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tcComplete =
          events.stream()
              .filter(e -> e instanceof StreamEvent.ToolCallComplete)
              .map(e -> (StreamEvent.ToolCallComplete) e)
              .findFirst()
              .orElseThrow();
      assertEquals("list_items", tcComplete.toolCall().name());
      assertEquals(Map.of(), tcComplete.toolCall().arguments());
    }
  }

  @org.junit.jupiter.api.Test
  void responseFailedEmitsError() {
    var failed =
        "data: {\"type\":\"response.failed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"failed\"}}\n\n";
    var sse = TEXT_DELTA + failed;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.Error));
    }
  }

  @org.junit.jupiter.api.Test
  void errorEventEmitsError() {
    var error = "data: {\"type\":\"error\",\"message\":\"something went wrong\"}\n\n";
    var sse = error + TEXT_DELTA + RESPONSE_COMPLETED;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertInstanceOf(StreamEvent.Error.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void textDeltaNullDeltaIsSkipped() {
    var nullDelta =
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
            + "\"content_index\":0}\n\n";
    var sse = nullDelta + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
      assertEquals("Hello", ((StreamEvent.TextDelta) events.get(0)).text());
    }
  }

  @org.junit.jupiter.api.Test
  void outputItemAddedNullItemIsSkipped() {
    var nullItem = "data: {\"type\":\"response.output_item.added\",\"output_index\":0}\n\n";
    var sse = nullItem + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void outputItemAddedNonFunctionCallIsSkipped() {
    var messageItem =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"message\",\"id\":\"msg_1\","
            + "\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}\n\n";
    var sse = messageItem + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void argsDeltaNullDeltaIsSkipped() {
    var nullDelta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\"}\n\n";
    var sse = nullDelta + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void argsDeltaNullItemIdIsSkipped() {
    var nullItemId =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"delta\":\"test\"}\n\n";
    var sse = nullItemId + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void argsDoneNullItemIdIsSkipped() {
    var nullItemId =
        "data: {\"type\":\"response.function_call_arguments.done\"," + "\"output_index\":0}\n\n";
    var sse = nullItemId + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void argsDoneUnknownItemIdIsSkipped() {
    var unknownId =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"unknown_id\"}\n\n";
    var sse = unknownId + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void responseCompletedNullResponseUsesDefaults() {
    var noResponse = "data: {\"type\":\"response.completed\"}\n\n";
    var sse = TEXT_DELTA + noResponse;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().usage());
      assertEquals(FinishReason.STOP, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void responseCompletedNullUsage() {
    var noUsage =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n";
    var sse = TEXT_DELTA + noUsage;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().usage());
    }
  }

  @org.junit.jupiter.api.Test
  void responseCompletedPartialUsageTokens() {
    var partialUsage =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"total_tokens\":10}}}\n\n";
    var sse = TEXT_DELTA + partialUsage;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(10, done.response().usage().inputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void reasoningSummaryNullTextIsSkipped() {
    var nullText =
        "data: {\"type\":\"response.reasoning_summary_text.delta\"," + "\"output_index\":0}\n\n";
    var sse = nullText + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNull(done.response().thinking());
    }
  }

  @org.junit.jupiter.api.Test
  void malformedToolCallArgsUseFallback() {
    var toolItemAdded =
        "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
            + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"test_fn\",\"arguments\":\"\","
            + "\"status\":\"in_progress\"}}\n\n";

    var argsDelta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"fc_1\","
            + "\"delta\":\"not valid json\"}\n\n";

    var argsDone =
        "data: {\"type\":\"response.function_call_arguments.done\","
            + "\"output_index\":0,\"item_id\":\"fc_1\"}\n\n";

    var sse = toolItemAdded + argsDelta + argsDone + RESPONSE_COMPLETED;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var tcComplete =
          events.stream()
              .filter(e -> e instanceof StreamEvent.ToolCallComplete)
              .map(e -> (StreamEvent.ToolCallComplete) e)
              .findFirst()
              .orElseThrow();
      assertTrue(tcComplete.toolCall().arguments().containsKey("_raw"));
      assertEquals("not valid json", tcComplete.toolCall().arguments().get("_raw"));
    }
  }

  @org.junit.jupiter.api.Test
  void argsDeltaUnknownAccumulatorIsIgnored() {
    var delta =
        "data: {\"type\":\"response.function_call_arguments.delta\","
            + "\"output_index\":0,\"item_id\":\"unknown_id\","
            + "\"delta\":\"some data\"}\n\n";
    var sse = delta + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void unknownEventTypeIsSkipped() {
    var unknown = "data: {\"type\":\"response.some_unknown_event\"}\n\n";
    var sse = unknown + TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void nextWithoutHasNextWorks() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var event = iterator.next();
      assertNotNull(event);
      assertInstanceOf(StreamEvent.TextDelta.class, event);
    }
  }

  @org.junit.jupiter.api.Test
  void hasNextCalledTwiceReturnsCachedEvent() {
    var sse = TEXT_DELTA + RESPONSE_COMPLETED;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.TextDelta.class, event);
    }
  }

  @org.junit.jupiter.api.Test
  void usageWithOnlyOutputTokens() {
    var response =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"output_tokens\":42,\"total_tokens\":42}}}\n\n";
    var sse = TEXT_DELTA + response;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(42, done.response().usage().outputTokens());
      assertEquals(0, done.response().usage().inputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void ioExceptionDuringReadEmitsError() throws Exception {
    var failingStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("Simulated network error");
          }
        };
    try (var iterator =
        new OpenAIModel.StreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
    }
  }

  @org.junit.jupiter.api.Test
  void incompleteStatusMapsToLength() {
    var incompleteResponse =
        "data: {\"type\":\"response.completed\",\"response\":{"
            + "\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"incomplete\","
            + "\"output\":[],\"model\":\"gpt-4o\","
            + "\"usage\":{\"input_tokens\":25,\"output_tokens\":4096,\"total_tokens\":4121}}}\n\n";

    var sse = TEXT_DELTA + incompleteResponse;

    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals(FinishReason.LENGTH, done.response().finishReason());
    }
  }

  private OpenAIModel.StreamingIterator createIterator(String sseData, Duration idleTimeout) {
    var inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
    return new OpenAIModel.StreamingIterator(fakeResponse(inputStream), objectMapper, idleTimeout);
  }

  private static HttpResponse<InputStream> fakeResponse(InputStream body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return body;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }
}
