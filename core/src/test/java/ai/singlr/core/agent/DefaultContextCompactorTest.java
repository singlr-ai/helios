/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.memory.MemoryEvent;
import ai.singlr.core.memory.MemoryListener;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Role;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DefaultContextCompactorTest {

  /**
   * Tiny model with a 1000-token contextWindow so we can trigger thresholds with modest message
   * volume. The chat handler is configurable so summary-failure paths can be exercised.
   */
  private static final class TestModel implements Model {
    private final int contextWindow;
    private final Function<List<Message>, Response<Void>> handler;
    final List<List<Message>> calls = new ArrayList<>();

    TestModel(int contextWindow, Function<List<Message>, Response<Void>> handler) {
      this.contextWindow = contextWindow;
      this.handler = handler;
    }

    static TestModel returning(int ctx, String summary) {
      return new TestModel(
          ctx,
          messages ->
              Response.newBuilder()
                  .withContent(summary)
                  .withFinishReason(FinishReason.STOP)
                  .build());
    }

    static TestModel failing(int ctx) {
      return new TestModel(
          ctx,
          messages -> {
            throw new RuntimeException("model unavailable");
          });
    }

    static TestModel returningBlank(int ctx) {
      return new TestModel(
          ctx,
          messages ->
              Response.newBuilder().withContent("").withFinishReason(FinishReason.STOP).build());
    }

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      calls.add(List.copyOf(messages));
      return handler.apply(messages);
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
  }

  /** Produce a list of synthetic messages totaling roughly {@code roughChars} characters. */
  private static List<Message> bigConversation(int turnCount, int contentChars) {
    var content = "x".repeat(contentChars);
    var msgs = new ArrayList<Message>();
    msgs.add(Message.system("system prompt"));
    msgs.add(Message.user("initial"));
    msgs.add(Message.assistant("initial response"));
    for (int i = 0; i < turnCount; i++) {
      msgs.add(Message.user("turn " + i + ": " + content));
      msgs.add(Message.assistant("response " + i + ": " + content));
    }
    return msgs;
  }

  // --- Threshold & no-op behaviour --------------------------------------------------------------

  @Test
  void noCompactionWhenBelowThreshold() {
    var compactor = new DefaultContextCompactor(TestModel.returning(1_000_000, "summary"));
    var messages = bigConversation(2, 10);

    var result = compactor.compactIfNeeded(messages);

    assertEquals(messages, result);
  }

  @Test
  void noCompactionWhenContextWindowIsZero() {
    var compactor =
        new DefaultContextCompactor(
            new TestModel(0, m -> Response.newBuilder().withContent("ignored").build()));
    var messages = bigConversation(10, 100);

    assertEquals(messages, compactor.compactIfNeeded(messages));
  }

  @Test
  void noCompactionForVeryShortConversations() {
    var compactor = new DefaultContextCompactor(TestModel.returning(100, "summary"));
    var short_ =
        List.of(Message.system("s"), Message.user("u"), Message.assistant("a"), Message.user("u2"));

    assertEquals(short_, compactor.compactIfNeeded(short_));
  }

  @Test
  void noOpCompactorAlwaysReturnsInput() {
    var messages = bigConversation(50, 200);
    assertEquals(
        messages, NoOpContextCompactor.INSTANCE.compactIfNeeded(messages, null, null, List.of()));
    assertEquals(messages, NoOpContextCompactor.INSTANCE.compactIfNeeded(messages));
  }

  // --- Phase 1: tool-result pruning -------------------------------------------------------------

  @Test
  void prunesOversizedToolResultsOutsideTail() {
    // Phase 1 only — ratio must cross earlyPrune but stay below summaryThreshold so the orphan
    // sanitization in Phase 4 doesn't run.
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(2000, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.99, 3, 3, 0.01, 50, 3));

    var parentCalls =
        List.of(
            ToolCall.newBuilder()
                .withId("call-1")
                .withName("search")
                .withArguments(Map.of())
                .build());
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1 calling", parentCalls));
    messages.add(Message.tool("call-1", "search", "X".repeat(600)));
    messages.add(Message.user("u2"));
    messages.add(Message.assistant("a2"));
    messages.add(Message.user("u3"));
    messages.add(Message.assistant("a3"));

    var result = compactor.compactIfNeeded(messages);

    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertTrue(tool.content().contains("cleared to save context space"));
    assertTrue(tool.content().contains("600 chars"));
  }

  @Test
  void doesNotPruneToolResultsInsideTail() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.99, 1, 6, 0.5, 50, 3));

    var bigContent = "X".repeat(1000);
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1"));
    messages.add(Message.user("u2"));
    messages.add(Message.assistant("a2"));
    messages.add(Message.user("u3"));
    messages.add(Message.tool("call-1", "search", bigContent));

    var result = compactor.compactIfNeeded(messages);

    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertEquals(bigContent, tool.content(), "tool result in protected tail must not be pruned");
  }

  @Test
  void preservesSmallToolResults() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.99, 1, 5, 0.1, 200, 3));

    var smallResult = "ok"; // under prune threshold
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1"));
    messages.add(Message.tool("call-1", "search", smallResult));
    messages.add(Message.user("u2"));
    messages.add(Message.assistant("a2"));
    messages.add(Message.user("u3"));
    messages.add(Message.assistant("a3"));

    var result = compactor.compactIfNeeded(messages);
    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertEquals(smallResult, tool.content());
  }

  private static List<Message> messagesWithOrphanFreeToolResult(int toolResultSize) {
    var parentCalls =
        List.of(
            ToolCall.newBuilder()
                .withId("call-1")
                .withName("search")
                .withArguments(Map.of())
                .build());
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1 calling", parentCalls));
    messages.add(Message.tool("call-1", "search", "X".repeat(toolResultSize)));
    messages.add(Message.user("u2"));
    messages.add(Message.assistant("a2"));
    messages.add(Message.user("u3"));
    messages.add(Message.assistant("a3"));
    return messages;
  }

  @Test
  void perToolResultCompactorReplacesGenericStub() {
    var customTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("desc")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("q")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(args -> ToolResult.success("never called in this test"))
            .withResultCompactor(raw -> "[custom-compacted, was " + raw.length() + "]")
            .build();
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(2000, "summary"),
            List.of(customTool),
            new CompactionConfig(0.05, 0.99, 3, 3, 0.01, 50, 3));

    var result = compactor.compactIfNeeded(messagesWithOrphanFreeToolResult(600));
    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertTrue(tool.content().startsWith("[custom-compacted"));
  }

  @Test
  void perToolResultCompactorReturningNullFallsBackToStub() {
    var customTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("desc")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("q")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(args -> ToolResult.success("ok"))
            .withResultCompactor(raw -> null)
            .build();
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(2000, "summary"),
            List.of(customTool),
            new CompactionConfig(0.05, 0.99, 3, 3, 0.01, 50, 3));

    var result = compactor.compactIfNeeded(messagesWithOrphanFreeToolResult(600));
    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertTrue(tool.content().contains("cleared to save context space"));
  }

  @Test
  void perToolResultCompactorThrowingFallsBackToStub() {
    var customTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("desc")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("q")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(args -> ToolResult.success("ok"))
            .withResultCompactor(
                raw -> {
                  throw new RuntimeException("compactor broken");
                })
            .build();
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(2000, "summary"),
            List.of(customTool),
            new CompactionConfig(0.05, 0.99, 3, 3, 0.01, 50, 3));

    var result = compactor.compactIfNeeded(messagesWithOrphanFreeToolResult(600));
    var tool = result.stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
    assertTrue(tool.content().contains("cleared to save context space"));
  }

  // --- Phase 3: structured summary --------------------------------------------------------------

  @Test
  void summarizesMiddleWhenAboveSummaryThreshold() {
    var summaryText = "## Goal\nTesting compaction\n## Critical Context\nDo not lose this";
    var model = TestModel.returning(200, summaryText);
    var compactor =
        new DefaultContextCompactor(
            model, List.of(), new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));

    var messages = bigConversation(10, 50);
    var result = compactor.compactIfNeeded(messages);

    var summary =
        result.stream()
            .filter(
                m ->
                    m.role() == Role.SYSTEM
                        && "true"
                            .equals(
                                m.metadata()
                                    .get(DefaultContextCompactor.COMPACTION_SUMMARY_MARKER)))
            .findFirst()
            .orElseThrow();
    assertTrue(summary.content().contains("Goal"));
    assertTrue(summary.content().contains("Critical Context"));
    assertEquals(1, model.calls.size());
  }

  @Test
  void summaryFailureFallsBackToPhase1OnlyAndIncrementsFailureCounter() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.failing(200), List.of(), new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));

    var messages = bigConversation(10, 50);
    var result = compactor.compactIfNeeded(messages);

    assertEquals(1, compactor.failureCount());
    // Summary message must NOT be present after a failure.
    assertTrue(
        result.stream()
            .noneMatch(
                m ->
                    m.role() == Role.SYSTEM
                        && "true"
                            .equals(
                                m.metadata()
                                    .get(DefaultContextCompactor.COMPACTION_SUMMARY_MARKER))));
  }

  @Test
  void blankSummaryFallsBackToPhase1OnlyAndIncrementsFailureCounter() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returningBlank(200),
            List.of(),
            new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));

    var result = compactor.compactIfNeeded(bigConversation(10, 50));

    assertEquals(1, compactor.failureCount());
    assertTrue(
        result.stream()
            .allMatch(
                m ->
                    m.role() != Role.SYSTEM
                        || !"true"
                            .equals(
                                m.metadata()
                                    .get(DefaultContextCompactor.COMPACTION_SUMMARY_MARKER))));
  }

  @Test
  void successResetsFailureCounter() {
    var summaries = new ArrayList<String>();
    summaries.add("");
    summaries.add("## Goal\nworking");
    var model =
        new TestModel(
            200,
            messages -> {
              var content = summaries.remove(0);
              return Response.newBuilder()
                  .withContent(content)
                  .withFinishReason(FinishReason.STOP)
                  .build();
            });
    var compactor =
        new DefaultContextCompactor(
            model, List.of(), new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));

    compactor.compactIfNeeded(bigConversation(10, 50));
    assertEquals(1, compactor.failureCount());

    compactor.compactIfNeeded(bigConversation(10, 50));
    assertEquals(0, compactor.failureCount());
  }

  @Test
  void priorSummaryIsFedIntoNextCompaction() {
    var captured = new ArrayList<String>();
    var model =
        new TestModel(
            50,
            messages -> {
              captured.add(messages.getFirst().content());
              return Response.newBuilder()
                  .withContent("## Goal\nIteration " + (captured.size()))
                  .build();
            });
    // protectLastN=10 ensures the post-compaction list is still > 5 messages so the second
    // compactIfNeeded call gets past the short-conversation early-return.
    var compactor =
        new DefaultContextCompactor(
            model, List.of(), new CompactionConfig(0.05, 0.05, 1, 10, 0.01, 50, 3));

    var first = compactor.compactIfNeeded(bigConversation(40, 80));
    var second = compactor.compactIfNeeded(first);

    assertEquals(2, captured.size(), "summary must fire twice to exercise carryover");
    assertFalse(captured.get(0).contains("PRIOR SUMMARY"));
    assertTrue(captured.get(1).contains("PRIOR SUMMARY"));
    assertTrue(
        captured.get(1).contains("Iteration 1"),
        "the prior summary content must appear in the second prompt");
    assertNotNull(second);
  }

  // --- Phase 4: orphan sanitization -------------------------------------------------------------

  @Test
  void sanitizeOrphansDropsToolResultsWithoutParentCall() {
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1")); // no tool call
    messages.add(Message.tool("orphan-call-id", "search", "result"));

    var result = DefaultContextCompactor.sanitizeOrphans(messages);

    assertEquals(3, result.size());
    assertTrue(result.stream().noneMatch(m -> m.role() == Role.TOOL));
  }

  @Test
  void sanitizeOrphansStubsToolCallsWithMissingResults() {
    var toolCalls =
        List.of(
            ToolCall.newBuilder()
                .withId("call-1")
                .withName("search")
                .withArguments(Map.of("q", "x"))
                .build());
    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("calling search", toolCalls));
    // tool result intentionally missing

    var result = DefaultContextCompactor.sanitizeOrphans(messages);

    assertEquals(4, result.size());
    var stub = result.getLast();
    assertEquals(Role.TOOL, stub.role());
    assertEquals("call-1", stub.toolCallId());
    assertTrue(stub.content().contains("pruned during context compaction"));
  }

  @Test
  void sanitizeOrphansKeepsValidToolCallResultPairs() {
    var toolCalls =
        List.of(ToolCall.newBuilder().withId("c1").withName("t").withArguments(Map.of()).build());
    var messages =
        List.of(
            Message.system("sys"),
            Message.assistant("calling", toolCalls),
            Message.tool("c1", "t", "ok"));

    var result = DefaultContextCompactor.sanitizeOrphans(messages);
    assertEquals(messages, result);
  }

  // --- Boundary alignment -----------------------------------------------------------------------

  @Test
  void computeTailStartAlignsBackwardPastToolResults() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "x"),
            List.of(),
            new CompactionConfig(0.05, 0.20, 1, 4, 0.1, 50, 3));

    var messages = new ArrayList<Message>();
    messages.add(Message.system("sys"));
    messages.add(Message.user("u1"));
    messages.add(Message.assistant("a1"));
    messages.add(
        Message.assistant(
            "a2",
            List.of(
                ToolCall.newBuilder().withId("c1").withName("t").withArguments(Map.of()).build())));
    messages.add(Message.tool("c1", "t", "tr1"));
    messages.add(Message.tool("c1", "t", "tr2")); // multi-result group
    messages.add(Message.user("u3"));

    int boundary = compactor.computeTailStart(messages);
    assertTrue(boundary < messages.size());
    // Boundary must land on the assistant message or before, not in the middle of tool messages.
    assertFalse(messages.get(boundary).role() == Role.TOOL);
  }

  // --- Listener fire points ---------------------------------------------------------------------

  @Test
  void firesBeforeCompactionToRegisteredListeners() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));
    var captured = new ArrayList<MemoryEvent.BeforeCompaction>();
    var listener =
        new MemoryListener() {
          @Override
          public void onBeforeCompaction(MemoryEvent.BeforeCompaction event) {
            captured.add(event);
          }
        };
    var userId = "u";
    var sessionId = UUID.randomUUID();

    var messages = bigConversation(10, 50);
    compactor.compactIfNeeded(messages, userId, sessionId, List.of(listener));

    assertEquals(1, captured.size());
    assertEquals(userId, captured.getFirst().userId());
    assertEquals(sessionId, captured.getFirst().sessionId());
    assertEquals(messages.size(), captured.getFirst().messages().size());
  }

  @Test
  void doesNotFireBeforeCompactionWhenBelowThreshold() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(1_000_000, "summary"), List.of(), CompactionConfig.defaults());
    var captured = new ArrayList<MemoryEvent.BeforeCompaction>();
    var listener =
        new MemoryListener() {
          @Override
          public void onBeforeCompaction(MemoryEvent.BeforeCompaction event) {
            captured.add(event);
          }
        };

    compactor.compactIfNeeded(bigConversation(3, 10), "u", UUID.randomUUID(), List.of(listener));

    assertTrue(captured.isEmpty());
  }

  @Test
  void beforeCompactionListenerExceptionIsSwallowed() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));
    var listener =
        new MemoryListener() {
          @Override
          public void onBeforeCompaction(MemoryEvent.BeforeCompaction event) {
            throw new RuntimeException("listener exploded");
          }
        };

    // Must not throw even though the listener does.
    var result =
        compactor.compactIfNeeded(
            bigConversation(10, 50), "u", UUID.randomUUID(), List.of(listener));
    assertNotNull(result);
  }

  // --- Config validation ------------------------------------------------------------------------

  @Test
  void rejectsNullModel() {
    assertThrows(IllegalArgumentException.class, () -> new DefaultContextCompactor(null));
  }

  @Test
  void rejectsNullConfig() {
    var model = TestModel.returning(100, "x");
    assertThrows(
        IllegalArgumentException.class, () -> new DefaultContextCompactor(model, List.of(), null));
  }

  @Test
  void compactionConfigRejectsInvalidThresholds() {
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0, 0.5, 1, 5, 0.1, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.3, 1, 5, 0.1, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 1.1, 1, 5, 0.1, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.9, -1, 5, 0.1, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.9, 1, -1, 0.1, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.9, 1, 5, 1.5, 50, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.9, 1, 5, 0.1, -1, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CompactionConfig(0.5, 0.9, 1, 5, 0.1, 50, -1));
  }

  @Test
  void defaultsAreValid() {
    var defaults = CompactionConfig.defaults();
    assertEquals(0.50, defaults.earlyPruneThreshold());
    assertEquals(0.85, defaults.summaryThreshold());
    assertEquals(3, defaults.protectFirstN());
    assertEquals(20, defaults.protectLastN());
    assertEquals(0.20, defaults.targetTailRatio());
    assertEquals(200, defaults.toolResultPruneSize());
    assertEquals(3, defaults.maxFailures());
  }

  @Test
  void configAccessorExposesActiveConfig() {
    var cfg = new CompactionConfig(0.4, 0.8, 2, 10, 0.15, 100, 2);
    var compactor = new DefaultContextCompactor(TestModel.returning(100, "x"), List.of(), cfg);
    assertEquals(cfg, compactor.config());
  }

  @Test
  void toolsAccessorExposesRegisteredToolsByName() {
    var t =
        Tool.newBuilder()
            .withName("zap")
            .withDescription("d")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("p")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor(a -> ToolResult.success("o"))
            .build();
    var compactor = new DefaultContextCompactor(TestModel.returning(100, "x"), List.of(t));
    assertTrue(compactor.tools().containsKey("zap"));
  }

  @Test
  void nullMessagesReturnsNull() {
    var compactor = new DefaultContextCompactor(TestModel.returning(100, "x"));
    assertEquals(null, compactor.compactIfNeeded(null));
  }

  @Test
  void nullToolsConstructorTreatedAsEmpty() {
    var compactor = new DefaultContextCompactor(TestModel.returning(100, "x"), null);
    assertTrue(compactor.tools().isEmpty());
  }

  @Test
  void emptyListenersDoesNotInvokeBeforeCompaction() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));
    // Should run without throwing despite empty listener list.
    var result = compactor.compactIfNeeded(bigConversation(10, 50), null, null, List.of());
    assertNotNull(result);
  }

  @Test
  void nullListenersDoesNotInvokeBeforeCompaction() {
    var compactor =
        new DefaultContextCompactor(
            TestModel.returning(200, "summary"),
            List.of(),
            new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));
    var result = compactor.compactIfNeeded(bigConversation(10, 50), null, null, null);
    assertNotNull(result);
  }

  // --- Failure circuit-breaker ------------------------------------------------------------------

  @Test
  void afterMaxFailuresCompactionFallsBackToPhase1Only() {
    var failingModel = TestModel.failing(200);
    var compactor =
        new DefaultContextCompactor(
            failingModel, List.of(), new CompactionConfig(0.05, 0.10, 1, 2, 0.1, 50, 3));

    for (int i = 0; i < 3; i++) {
      compactor.compactIfNeeded(bigConversation(10, 50));
    }
    assertEquals(3, compactor.failureCount());

    // Breaker tripped — fifth invocation must not even attempt the model call.
    int callsBefore = failingModel.calls.size();
    compactor.compactIfNeeded(bigConversation(10, 50));
    assertEquals(callsBefore, failingModel.calls.size());
  }
}
