/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
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
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class StreamingIteratorTest {

  private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

  private static final String TEXT_DELTA_EVENT =
      "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"text\",\"text\":\"Hello\"}}\n\n";

  private static final String TOOL_CALL_EVENT =
      "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"function_call\","
          + "\"id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":{\"city\":\"NYC\"}}}\n\n";

  private static final String THOUGHT_EVENT =
      "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"thought\","
          + "\"summary\":\"thinking...\",\"signature\":\"sig123\"}}\n\n";

  private static final String COMPLETE_EVENT =
      "data: {\"event_type\":\"interaction.complete\",\"interaction\":{\"outputs\":[],"
          + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5}}}\n\n";

  private static final String COMPLETE_FAILED_EVENT =
      "data: {\"event_type\":\"interaction.complete\",\"interaction\":{\"outputs\":[],"
          + "\"usage\":{\"total_input_tokens\":10,\"total_output_tokens\":5},\"status\":\"failed\"}}\n\n";

  private final tools.jackson.databind.ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @org.junit.jupiter.api.Test
  void textDeltaEvents() {
    var sse = TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
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
  void toolCallEvent() {
    var sse = TOOL_CALL_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(0));
      var tc = ((StreamEvent.ToolCallComplete) events.get(0)).toolCall();
      assertEquals("get_weather", tc.name());
      assertEquals("call_1", tc.id());
      assertEquals(Map.of("city", "NYC"), tc.arguments());

      var done = (StreamEvent.Done) events.get(1);
      assertEquals(FinishReason.TOOL_CALLS, done.response().finishReason());
      assertFalse(done.response().toolCalls().isEmpty());
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtEventCapturesSignature() {
    var sse = THOUGHT_EVENT + TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }

      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().thinking());
      assertTrue(done.response().thinking().contains("thinking..."));

      var metadata = done.response().metadata();
      assertTrue(metadata.containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
      assertEquals("sig123", metadata.get(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void thoughtSignatureTypeCapturesSignature() {
    var sigEvent =
        "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"thought_signature\","
            + "\"signature\":\"sig456\"}}\n\n";
    var sse = sigEvent + TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }

      var done = (StreamEvent.Done) events.getLast();
      var metadata = done.response().metadata();
      assertTrue(metadata.containsKey(GeminiModel.THOUGHT_SIGNATURES_KEY));
      assertEquals("sig456", metadata.get(GeminiModel.THOUGHT_SIGNATURES_KEY));
    }
  }

  @org.junit.jupiter.api.Test
  void usageFromCompleteEvent() {
    var sse = TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertNotNull(done.response().usage());
      assertEquals(10, done.response().usage().inputTokens());
      assertEquals(5, done.response().usage().outputTokens());
    }
  }

  @org.junit.jupiter.api.Test
  void failedInteractionSetsErrorFinishReason() {
    var sse = COMPLETE_FAILED_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(1, events.size());
      var done = (StreamEvent.Done) events.getFirst();
      assertEquals(FinishReason.ERROR, done.response().finishReason());
    }
  }

  @org.junit.jupiter.api.Test
  void emptyAndDoneDataLinesAreSkipped() {
    var sse = "data: \n\ndata: [DONE]\n\n" + TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    }
  }

  @org.junit.jupiter.api.Test
  void nonDataLinesAreIgnored() {
    var sse = "event: content.delta\n" + TEXT_DELTA_EVENT + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
    }
  }

  @org.junit.jupiter.api.Test
  void malformedJsonEmitsErrorEvent() {
    var sse = "data: {not valid json}\n\n" + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      assertEquals(2, events.size());
      assertInstanceOf(StreamEvent.Error.class, events.get(0));
      assertInstanceOf(StreamEvent.Done.class, events.get(1));
    }
  }

  @org.junit.jupiter.api.Test
  void idleTimeoutEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);

    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(pipedIn), objectMapper, SHORT_IDLE_TIMEOUT)) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("idle timeout"));
      assertInstanceOf(GeminiException.class, error.cause());
      assertTrue(((GeminiException) error.cause()).isRetryable());
    }
    pipedOut.close();
  }

  @org.junit.jupiter.api.Test
  void closeIsIdempotent() {
    var sse = TEXT_DELTA_EVENT + COMPLETE_EVENT;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    iterator.close();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void closeAfterPartialConsumption() {
    var sse = TEXT_DELTA_EVENT + TEXT_DELTA_EVENT + COMPLETE_EVENT;
    var iterator = createIterator(sse, Duration.ofSeconds(5));
    assertTrue(iterator.hasNext());
    iterator.next();
    iterator.close();
    assertFalse(iterator.hasNext());
  }

  @org.junit.jupiter.api.Test
  void multipleTextDeltas() {
    var sse =
        "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"text\",\"text\":\"Hello \"}}\n\n"
            + "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"text\",\"text\":\"World\"}}\n\n"
            + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
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
    var sse = COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
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
  void textAnnotationDeltaHarvestsCitations() {
    // Reproduces the real Gemini Interactions API shape: citations arrive as
    // content.delta events with type=text_annotation, not on interaction.complete.
    // Regression guard for the bug Kubera reported where Gemini 3.x grounded
    // responses surfaced zero citations in traces.
    var annotationEvent =
        "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"text_annotation\","
            + "\"annotations\":["
            + "{\"url\":\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc\","
            + "\"title\":\"wikipedia.org\",\"type\":\"url_citation\","
            + "\"start_index\":0,\"end_index\":120},"
            + "{\"url\":\"https://vertexaisearch.cloud.google.com/grounding-api-redirect/xyz\","
            + "\"title\":\"forbes.com\",\"type\":\"url_citation\","
            + "\"start_index\":121,\"end_index\":240}"
            + "]}}\n\n";
    var sse = TEXT_DELTA_EVENT + annotationEvent + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertTrue(done.response().hasCitations(), "Expected citations from text_annotation delta");
      assertEquals(2, done.response().citations().size());

      var first = done.response().citations().get(0);
      assertEquals("wikipedia.org", first.title());
      assertTrue(first.sourceId().startsWith("https://vertexaisearch.cloud.google.com"));
      assertEquals(0, first.startIndex());
      assertEquals(120, first.endIndex());

      var second = done.response().citations().get(1);
      assertEquals("forbes.com", second.title());
    }
  }

  @org.junit.jupiter.api.Test
  void textAnnotationDeltaSkipsNonUrlCitationAnnotations() {
    var annotationEvent =
        "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"text_annotation\","
            + "\"annotations\":["
            + "{\"url\":\"https://a\",\"title\":\"a\",\"type\":\"other_annotation\"},"
            + "{\"url\":\"https://b\",\"title\":\"b.com\",\"type\":\"url_citation\"}"
            + "]}}\n\n";
    var sse = TEXT_DELTA_EVENT + annotationEvent + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      while (iterator.hasNext()) {
        iterator.next();
      }
    }
  }

  @org.junit.jupiter.api.Test
  void googleSearchResultDeltaDoesNotBreakParsing() {
    // google_search_result deltas carry HTML chip UI — they must not be
    // harvested as citations and must not corrupt the text stream.
    var searchResultEvent =
        "data: {\"event_type\":\"content.delta\",\"delta\":{\"type\":\"google_search_result\"}}"
            + "\n\n";
    var sse = TEXT_DELTA_EVENT + searchResultEvent + COMPLETE_EVENT;
    try (var iterator = createIterator(sse, Duration.ofSeconds(5))) {
      var events = new java.util.ArrayList<StreamEvent>();
      while (iterator.hasNext()) {
        events.add(iterator.next());
      }
      var done = (StreamEvent.Done) events.getLast();
      assertEquals("Hello", done.response().content());
      assertFalse(done.response().hasCitations());
    }
  }

  @org.junit.jupiter.api.Test
  void ioExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() throws java.io.IOException {
            throw new java.io.IOException("Simulated I/O failure");
          }
        };
    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void runtimeExceptionFromReaderEmitsErrorEvent() {
    var failingStream =
        new InputStream() {
          @Override
          public int read() {
            throw new RuntimeException("Unexpected failure");
          }
        };
    try (var iterator =
        new GeminiModel.StreamingIterator(
            fakeResponse(failingStream), objectMapper, Duration.ofSeconds(5))) {
      assertTrue(iterator.hasNext());
      var event = iterator.next();
      assertInstanceOf(StreamEvent.Error.class, event);
      var error = (StreamEvent.Error) event;
      assertTrue(error.message().contains("Stream read error"));
    }
  }

  @org.junit.jupiter.api.Test
  void interruptedThreadEmitsErrorEvent() throws Exception {
    var pipedIn = new PipedInputStream();
    var pipedOut = new PipedOutputStream(pipedIn);
    var events = new java.util.ArrayList<StreamEvent>();
    var thread =
        new Thread(
            () -> {
              try (var iterator =
                  new GeminiModel.StreamingIterator(
                      fakeResponse(pipedIn), objectMapper, Duration.ofSeconds(30))) {
                while (iterator.hasNext()) {
                  events.add(iterator.next());
                }
              }
            });
    thread.start();
    Thread.sleep(100);
    thread.interrupt();
    thread.join(5000);
    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    pipedOut.close();
  }

  private GeminiModel.StreamingIterator createIterator(String sseData, Duration idleTimeout) {
    var inputStream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));
    return new GeminiModel.StreamingIterator(fakeResponse(inputStream), objectMapper, idleTimeout);
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
