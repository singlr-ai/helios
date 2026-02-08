/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

  @Test
  void systemMessage() {
    var msg = Message.system("You are a helpful assistant.");

    assertEquals(Role.SYSTEM, msg.role());
    assertEquals("You are a helpful assistant.", msg.content());
    assertTrue(msg.toolCalls().isEmpty());
    assertNull(msg.toolCallId());
    assertFalse(msg.hasToolCalls());
  }

  @Test
  void userMessage() {
    var msg = Message.user("Hello!");

    assertEquals(Role.USER, msg.role());
    assertEquals("Hello!", msg.content());
  }

  @Test
  void assistantMessage() {
    var msg = Message.assistant("Hi there!");

    assertEquals(Role.ASSISTANT, msg.role());
    assertEquals("Hi there!", msg.content());
  }

  @Test
  void assistantWithToolCalls() {
    var toolCall =
        ToolCall.newBuilder()
            .withId("call_123")
            .withName("search")
            .withArguments(Map.of("query", "weather"))
            .build();

    var msg = Message.assistant(List.of(toolCall));

    assertEquals(Role.ASSISTANT, msg.role());
    assertNull(msg.content());
    assertTrue(msg.hasToolCalls());
    assertEquals(1, msg.toolCalls().size());
    assertEquals("search", msg.toolCalls().getFirst().name());
  }

  @Test
  void toolResultMessage() {
    var msg = Message.tool("call_123", "get_weather", "The weather is sunny.");

    assertEquals(Role.TOOL, msg.role());
    assertEquals("The weather is sunny.", msg.content());
    assertEquals("call_123", msg.toolCallId());
    assertEquals("get_weather", msg.toolName());
  }

  @Test
  void builderPattern() {
    var msg = Message.newBuilder().withRole(Role.USER).withContent("Test message").build();

    assertEquals(Role.USER, msg.role());
    assertEquals("Test message", msg.content());
  }

  @Test
  void copyBuilder() {
    var original = Message.user("Original");
    var copy = Message.newBuilder(original).withContent("Modified").build();

    assertEquals("Original", original.content());
    assertEquals("Modified", copy.content());
    assertEquals(Role.USER, copy.role());
  }

  @Test
  void allRoles() {
    assertEquals(Role.SYSTEM, Role.valueOf("SYSTEM"));
    assertEquals(Role.USER, Role.valueOf("USER"));
    assertEquals(Role.ASSISTANT, Role.valueOf("ASSISTANT"));
    assertEquals(Role.TOOL, Role.valueOf("TOOL"));
    assertEquals(4, Role.values().length);
  }

  @Test
  void builderWithToolCallId() {
    var msg =
        Message.newBuilder()
            .withRole(Role.TOOL)
            .withContent("Result")
            .withToolCallId("call_123")
            .build();

    assertEquals(Role.TOOL, msg.role());
    assertEquals("call_123", msg.toolCallId());
  }

  @Test
  void builderWithToolCalls() {
    var toolCall =
        ToolCall.newBuilder().withId("call_1").withName("test").withArguments(Map.of()).build();
    var msg =
        Message.newBuilder().withRole(Role.ASSISTANT).withToolCalls(List.of(toolCall)).build();

    assertTrue(msg.hasToolCalls());
    assertEquals(1, msg.toolCalls().size());
  }

  @Test
  void assistantWithContentAndToolCalls() {
    var toolCall = ToolCall.newBuilder().withId("call_1").withName("test").build();
    var msg = Message.assistant("Thinking...", List.of(toolCall));

    assertEquals(Role.ASSISTANT, msg.role());
    assertEquals("Thinking...", msg.content());
    assertTrue(msg.hasToolCalls());
  }

  @Test
  void hasToolCallsWithNullToolCalls() {
    var msg = new Message(Role.ASSISTANT, "content", null, null, null, null);

    assertFalse(msg.hasToolCalls());
  }

  @Test
  void builderWithNullToolCalls() {
    var msg = Message.newBuilder().withRole(Role.ASSISTANT).withToolCalls(null).build();

    assertFalse(msg.hasToolCalls());
    assertTrue(msg.toolCalls().isEmpty());
  }

  @Test
  void builderWithoutRoleThrows() {
    assertThrows(
        IllegalStateException.class, () -> Message.newBuilder().withContent("no role").build());
  }
}
