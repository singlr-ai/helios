/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextCompactorTest {

  private Model modelWithContextWindow(int contextWindow) {
    return modelWithContextWindow(contextWindow, "Summary of conversation");
  }

  private Model modelWithContextWindow(int contextWindow, String summaryResponse) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(summaryResponse)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "test-model";
      }

      @Override
      public String provider() {
        return "test";
      }

      @Override
      public int contextWindow() {
        return contextWindow;
      }
    };
  }

  private Model failingModel(int contextWindow) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        throw new RuntimeException("Model unavailable");
      }

      @Override
      public String id() {
        return "test-model";
      }

      @Override
      public String provider() {
        return "test";
      }

      @Override
      public int contextWindow() {
        return contextWindow;
      }
    };
  }

  @Test
  void noCompactionWhenContextWindowZero() {
    var model = modelWithContextWindow(0);
    var compactor = new ContextCompactor(model);
    var messages =
        List.of(
            Message.system("sys"),
            Message.user("hi"),
            Message.assistant("hello"),
            Message.user("more"),
            Message.assistant("more back"),
            Message.user("again"));

    var result = compactor.compactIfNeeded(messages);

    assertSame(messages, result);
  }

  @Test
  void noCompactionWhenFewMessages() {
    var model = modelWithContextWindow(100);
    var compactor = new ContextCompactor(model);
    var messages = List.of(Message.system("sys"), Message.user("hi"), Message.assistant("hello"));

    var result = compactor.compactIfNeeded(messages);

    assertSame(messages, result);
  }

  @Test
  void noCompactionWhenBelowThreshold() {
    var model = modelWithContextWindow(1_000_000);
    var compactor = new ContextCompactor(model);
    var messages =
        List.of(
            Message.system("System prompt"),
            Message.user("Hello"),
            Message.assistant("Hi there"),
            Message.user("How are you?"),
            Message.assistant("I'm fine"),
            Message.user("Great"));

    var result = compactor.compactIfNeeded(messages);

    assertSame(messages, result);
  }

  @Test
  void microCompactDropsOldToolResults() {
    // ~69 estimated tokens; contextWindow=80 → ratio 0.86 (micro-compact, not auto)
    var model = modelWithContextWindow(80);
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("tool1")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "tool1", "A".repeat(200))); // old tool result
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c2")
                    .withName("tool2")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c2", "tool2", "Recent result"));
    messages.add(Message.assistant("Done"));
    messages.add(Message.user("Thanks"));

    var result = compactor.compactIfNeeded(messages);

    // Old tool result (index 3) should be replaced
    assertEquals("[result omitted]", result.get(3).content());
    assertEquals(Role.TOOL, result.get(3).role());
    assertEquals("tool1", result.get(3).toolName());

    // Recent messages should be preserved (last 4)
    assertEquals("Recent result", result.get(7).content());
  }

  @Test
  void microCompactPreservesSystemMessage() {
    // ~53 estimated tokens; contextWindow=60 → ratio 0.88 (micro-compact)
    var model = modelWithContextWindow(60);
    var compactor = new ContextCompactor(model);
    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.tool("c1", "tool1", "Old result"));
    messages.add(Message.user("q1"));
    messages.add(Message.assistant("a1"));
    messages.add(Message.user("q2"));
    messages.add(Message.assistant("a2"));
    messages.add(Message.user("q3"));
    messages.add(Message.assistant("a3" + "X".repeat(200)));

    var result = compactor.compactIfNeeded(messages);

    // System message (index 0) must not be touched even if it's a TOOL role
    assertEquals("System", result.get(0).content());
    // Tool at index 1 (old, before cutoff) should be replaced
    assertEquals("[result omitted]", result.get(1).content());
  }

  @Test
  void autoCompactSummarizesOlderMessages() {
    // Create model with very small context window
    var model = modelWithContextWindow(20, "Discussed weather and greetings");
    var compactor = new ContextCompactor(model);

    // Build messages with enough content to exceed 90% of 20 tokens
    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.user("Hello " + "X".repeat(60)));
    messages.add(Message.assistant("World " + "Y".repeat(60)));
    messages.add(Message.user("More " + "Z".repeat(60)));
    messages.add(Message.assistant("Even more"));
    messages.add(Message.user("Recent 1"));
    messages.add(Message.assistant("Recent 2"));
    messages.add(Message.user("Recent 3"));
    messages.add(Message.assistant("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    // Should have: system + summary + last 4 = 6 messages
    assertEquals(6, result.size());
    assertEquals("System", result.get(0).content());
    assertTrue(result.get(1).content().contains("Conversation Summary"));
    assertTrue(result.get(1).content().contains("Discussed weather and greetings"));
    // Last 4 preserved
    assertEquals("Recent 1", result.get(2).content());
    assertEquals("Recent 2", result.get(3).content());
    assertEquals("Recent 3", result.get(4).content());
    assertEquals("Recent 4", result.get(5).content());
  }

  @Test
  void autoCompactFallsBackToMicroOnModelFailure() {
    var model = failingModel(20);
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.user("U1"));
    messages.add(Message.assistant("A1"));
    messages.add(Message.tool("c1", "t1", "Old tool data " + "X".repeat(100)));
    messages.add(Message.user("U2"));
    messages.add(Message.assistant("A2"));
    messages.add(Message.user("U3"));
    messages.add(Message.assistant("A3"));

    var result = compactor.compactIfNeeded(messages);

    // Should fall back to micro-compact, tool result at index 3 replaced
    assertEquals("[result omitted]", result.get(3).content());
    assertEquals(messages.size(), result.size());
  }

  @Test
  void autoCompactCircuitBreakerTripsAfterMaxFailures() {
    var model = failingModel(20);
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    for (var i = 0; i < 6; i++) {
      messages.add(Message.user("U" + i + "X".repeat(20)));
      messages.add(Message.assistant("A" + i + "Y".repeat(20)));
    }

    // Trigger 3 failures to trip circuit breaker
    for (var i = 0; i < ContextCompactor.MAX_FAILURES; i++) {
      compactor.compactIfNeeded(messages);
    }

    // After circuit breaker trips, should not attempt auto-compact
    // (falls through to micro-compact instead)
    var result = compactor.compactIfNeeded(messages);
    // Result should still be valid (micro-compact or original)
    assertEquals(messages.size(), result.size());
  }

  @Test
  void autoCompactFallsBackOnBlankSummary() {
    var model = modelWithContextWindow(20, "   ");
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.user("U1" + "X".repeat(100)));
    messages.add(Message.assistant("A1"));
    messages.add(Message.tool("c1", "t1", "Old data"));
    messages.add(Message.user("U2"));
    messages.add(Message.assistant("A2"));
    messages.add(Message.user("U3"));
    messages.add(Message.assistant("A3"));

    var result = compactor.compactIfNeeded(messages);

    // Blank summary → falls back to micro-compact
    assertEquals(messages.size(), result.size());
  }

  @Test
  void autoCompactFallsBackOnNullSummary() {
    var model = modelWithContextWindow(20, null);
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.user("U1" + "X".repeat(100)));
    messages.add(Message.assistant("A1"));
    messages.add(Message.tool("c1", "t1", "Old data"));
    messages.add(Message.user("U2"));
    messages.add(Message.assistant("A2"));
    messages.add(Message.user("U3"));
    messages.add(Message.assistant("A3"));

    var result = compactor.compactIfNeeded(messages);

    // Null summary → falls back to micro-compact
    assertEquals(messages.size(), result.size());
  }

  @Test
  void microCompactReplacesToolBeforeCutoff() {
    // ~51 estimated tokens; contextWindow=60 → ratio 0.85 (micro-compact)
    var model = modelWithContextWindow(60);
    var compactor = new ContextCompactor(model);

    // 6 messages, cutoff = 6 - 4 = 2, tool at index 1 is before cutoff
    var messages = new ArrayList<Message>();
    messages.add(Message.system("S" + "X".repeat(200)));
    messages.add(Message.tool("c1", "t", "data"));
    messages.add(Message.user("u"));
    messages.add(Message.assistant("a"));
    messages.add(Message.user("u2"));
    messages.add(Message.assistant("a2"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals("[result omitted]", result.get(1).content());
  }

  @Test
  void autoCompactHandlesNullContentMessages() {
    var model = modelWithContextWindow(20, "Summary with null content");
    var compactor = new ContextCompactor(model);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(
        Message.assistant(
            null,
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("tool1")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "tool1", "result" + "X".repeat(100)));
    messages.add(Message.user("U1" + "Y".repeat(50)));
    messages.add(Message.assistant("A1"));
    messages.add(Message.user("Recent 1"));
    messages.add(Message.assistant("Recent 2"));
    messages.add(Message.user("Recent 3"));
    messages.add(Message.assistant("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals(6, result.size());
    assertTrue(result.get(1).content().contains("Summary with null content"));
  }

  @Test
  void exactlyFiveMessagesReturnsOriginal() {
    var model = modelWithContextWindow(100);
    var compactor = new ContextCompactor(model);
    var messages =
        List.of(
            Message.system("S"),
            Message.user("u1"),
            Message.assistant("a1"),
            Message.user("u2"),
            Message.assistant("a2"));

    var result = compactor.compactIfNeeded(messages);

    assertSame(messages, result);
  }
}
