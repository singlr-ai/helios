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
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  void successfulAutoCompactResetsFailureCounter() {
    // A model that fails twice, succeeds on the third call, then fails forever. Without the reset
    // on success, the failure counter would reach MAX_FAILURES on iteration 3 and the breaker would
    // trip — chat() would not be called on iterations 4-5. With the reset, the success on
    // iteration 2 wipes the counter and chat() is called every iteration through 5.
    var callCount = new AtomicInteger();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var n = callCount.incrementAndGet();
            if (n == 3) {
              return Response.newBuilder()
                  .withContent("recovery summary")
                  .withFinishReason(FinishReason.STOP)
                  .build();
            }
            throw new RuntimeException("transient failure #" + n);
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
            return 20;
          }
        };
    var compactor = new ContextCompactor(model);
    var messages = new ArrayList<Message>();
    messages.add(Message.system("System"));
    messages.add(Message.user("U1" + "X".repeat(60)));
    messages.add(Message.assistant("A1" + "Y".repeat(60)));
    messages.add(Message.user("U2" + "Z".repeat(60)));
    messages.add(Message.assistant("A2"));
    messages.add(Message.user("R1"));
    messages.add(Message.assistant("R2"));
    messages.add(Message.user("R3"));
    messages.add(Message.assistant("R4"));

    for (var i = 0; i < 6; i++) {
      compactor.compactIfNeeded(messages);
    }

    assertEquals(
        6,
        callCount.get(),
        "auto-compact should be attempted on every iteration; success on the third call must reset"
            + " the counter so the breaker doesn't trip on iteration 4");
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
  void microCompactUsesPerToolCompactorWhenRegistered() {
    // failingModel makes auto-compact always fall back to micro-compact, so the test only depends
    // on micro-compact triggering — not on hitting an exact ratio between the 0.75 and 0.90
    // thresholds. The tool's custom compactor must produce the replacement content.
    var model = failingModel(80);
    var richTool =
        Tool.newBuilder()
            .withName("execute_code")
            .withDescription("rich")
            .withExecutor(args -> ToolResult.success(""))
            .withResultCompactor(content -> "[meta: len=" + content.length() + "]")
            .build();
    var compactor = new ContextCompactor(model, List.of(richTool));

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("execute_code")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "execute_code", "x".repeat(200)));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals(
        "[meta: len=200]",
        result.get(3).content(),
        "old execute_code result should be replaced by the tool's custom compactor, not the"
            + " constant placeholder");
    assertEquals("execute_code", result.get(3).toolName());
  }

  @Test
  void microCompactFallsBackToPlaceholderForUnregisteredTool() {
    // Tool name on the message has no matching entry in the tools map → legacy "[result omitted]"
    var model = failingModel(80);
    var compactor = new ContextCompactor(model, List.of());

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("unknown_tool")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "unknown_tool", "X".repeat(200)));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals("[result omitted]", result.get(3).content());
  }

  @Test
  void microCompactFallsBackWhenCompactorThrows() {
    // A tool whose compactor throws must not abort the whole compaction pass — the caller's run
    // is still in progress and a misbehaving compactor is a tooling bug, not a fatal one.
    var model = failingModel(80);
    var brokenTool =
        Tool.newBuilder()
            .withName("broken")
            .withDescription("explodes")
            .withExecutor(args -> ToolResult.success(""))
            .withResultCompactor(
                content -> {
                  throw new RuntimeException("compactor exploded");
                })
            .build();
    var compactor = new ContextCompactor(model, List.of(brokenTool));

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("broken")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "broken", "data" + "Y".repeat(200)));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals(
        "[result omitted]",
        result.get(3).content(),
        "compactor exception must fall back to the constant placeholder, not propagate");
  }

  @Test
  void microCompactFallsBackWhenCompactorReturnsNull() {
    var model = failingModel(80);
    var nullishTool =
        Tool.newBuilder()
            .withName("nullish")
            .withDescription("returns null")
            .withExecutor(args -> ToolResult.success(""))
            .withResultCompactor(content -> null)
            .build();
    var compactor = new ContextCompactor(model, List.of(nullishTool));

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("nullish")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "nullish", "data" + "Z".repeat(200)));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals("[result omitted]", result.get(3).content());
  }

  @Test
  void compactorReceivesEmptyStringWhenToolContentIsNull() {
    // Defensive: Message.tool(_, _, null) is legal — the compactor must see an empty string, not
    // null, so user-supplied compactor lambdas don't have to null-guard.
    var model = failingModel(80);
    var captured = new AtomicReference<String>();
    var tool =
        Tool.newBuilder()
            .withName("capturing")
            .withDescription("captures input")
            .withExecutor(args -> ToolResult.success(""))
            .withResultCompactor(
                content -> {
                  captured.set(content);
                  return "captured";
                })
            .build();
    var compactor = new ContextCompactor(model, List.of(tool));

    var messages = new ArrayList<Message>();
    // The system + user content together must push estimated tokens above ~60 (75% of cw=80) to
    // trigger micro-compact. The tool message itself contributes 0 tokens (null content), which
    // is exactly the case under test.
    messages.add(Message.system("System prompt" + "S".repeat(120)));
    messages.add(Message.user("Do something" + "U".repeat(120)));
    messages.add(
        Message.assistant(
            "",
            List.of(
                ToolCall.newBuilder()
                    .withId("c1")
                    .withName("capturing")
                    .withArguments(Map.of())
                    .build())));
    messages.add(Message.tool("c1", "capturing", null));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);

    assertEquals(
        "",
        captured.get(),
        "null TOOL message content must be coerced to empty string before the compactor sees it");
    assertEquals("captured", result.get(3).content());
  }

  @Test
  void newConstructorWithNullToolsIsTolerated() {
    // Defensive: a caller passing null where an empty list was meant must not NPE — the existing
    // single-arg ctor already accepts only the model, so callers may convert to the new ctor and
    // forget to substitute List.of() for null.
    var model = failingModel(80);
    var compactor = new ContextCompactor(model, null);

    var messages = new ArrayList<Message>();
    messages.add(Message.system("System prompt here"));
    messages.add(Message.user("Do something"));
    messages.add(Message.tool("c1", "anything", "X".repeat(250)));
    messages.add(Message.assistant("Result noted"));
    messages.add(Message.user("Now what?"));
    messages.add(Message.assistant("Recent 1"));
    messages.add(Message.user("Recent 2"));
    messages.add(Message.assistant("Recent 3"));
    messages.add(Message.user("Recent 4"));

    var result = compactor.compactIfNeeded(messages);
    assertEquals("[result omitted]", result.get(2).content());
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
