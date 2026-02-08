/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamEventTest {

  @Test
  void textDelta() {
    var event = new StreamEvent.TextDelta("Hello");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Hello", event.text());
  }

  @Test
  void toolCallStart() {
    var event = new StreamEvent.ToolCallStart("call_123", "search");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("call_123", event.callId());
    assertEquals("search", event.toolName());
  }

  @Test
  void toolCallDelta() {
    var event = new StreamEvent.ToolCallDelta("call_123", "{\"query\":");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("call_123", event.callId());
    assertEquals("{\"query\":", event.argumentsDelta());
  }

  @Test
  void toolCallComplete() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(Map.of("query", "test"))
            .build();
    var event = new StreamEvent.ToolCallComplete(toolCall);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals(toolCall, event.toolCall());
  }

  @Test
  void done() {
    var response =
        Response.newBuilder().withContent("Done!").withFinishReason(FinishReason.STOP).build();
    var event = new StreamEvent.Done(response);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals(response, event.response());
  }

  @Test
  void errorWithCause() {
    var cause = new RuntimeException("Network error");
    var event = new StreamEvent.Error("Connection failed", cause);

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Connection failed", event.message());
    assertEquals(cause, event.cause());
  }

  @Test
  void errorWithoutCause() {
    var event = new StreamEvent.Error("Timeout occurred");

    assertInstanceOf(StreamEvent.class, event);
    assertEquals("Timeout occurred", event.message());
    assertNull(event.cause());
  }

  @Test
  void patternMatchingOnEvents() {
    StreamEvent textEvent = new StreamEvent.TextDelta("text");
    StreamEvent errorEvent = new StreamEvent.Error("error");

    var textResult =
        switch (textEvent) {
          case StreamEvent.TextDelta(var text) -> "text: " + text;
          case StreamEvent.ToolCallStart(var id, var name) -> "start";
          case StreamEvent.ToolCallDelta(var id, var delta) -> "delta";
          case StreamEvent.ToolCallComplete(var tc) -> "complete";
          case StreamEvent.Done(var r) -> "done";
          case StreamEvent.Error(var msg, var cause) -> "error: " + msg;
        };

    var errorResult =
        switch (errorEvent) {
          case StreamEvent.TextDelta(var text) -> "text";
          case StreamEvent.ToolCallStart(var id, var name) -> "start";
          case StreamEvent.ToolCallDelta(var id, var delta) -> "delta";
          case StreamEvent.ToolCallComplete(var tc) -> "complete";
          case StreamEvent.Done(var r) -> "done";
          case StreamEvent.Error(var msg, var cause) -> "error: " + msg;
        };

    assertEquals("text: text", textResult);
    assertEquals("error: error", errorResult);
  }
}
