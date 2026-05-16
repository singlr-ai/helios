/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import ai.singlr.core.fault.Backoff;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.fault.RetryPolicy;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.AgentRunStatus;
import ai.singlr.core.runtime.Durability;
import ai.singlr.core.runtime.InMemoryRunStore;
import ai.singlr.core.runtime.InMemoryToolCallJournal;
import ai.singlr.core.runtime.ToolCallRecord;
import ai.singlr.core.runtime.ToolCallStatus;
import ai.singlr.core.runtime.UnsafeResumeException;
import ai.singlr.core.runtime.UnsafeResumePolicy;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentDurabilityTest {

  private static Model toolThenStop(String toolName, String toolCallId) {
    var calls = new AtomicInteger(0);
    return new Model() {
      @Override
      public Response chat(List<Message> messages, List<Tool> tools) {
        if (calls.getAndIncrement() == 0) {
          return Response.newBuilder()
              .withToolCalls(
                  List.of(
                      ToolCall.newBuilder()
                          .withId(toolCallId)
                          .withName(toolName)
                          .withArguments(Map.of())
                          .build()))
              .withFinishReason(FinishReason.TOOL_CALLS)
              .build();
        }
        return Response.newBuilder()
            .withContent("done")
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  private static Model alwaysStop(String content) {
    return new Model() {
      @Override
      public Response chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(content)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  @Test
  void runWithExplicitRunIdRequiresDurability() {
    var agent = new Agent(AgentConfig.newBuilder().withModel(alwaysStop("ok")).build());
    assertThrows(
        IllegalStateException.class, () -> agent.run(SessionContext.of("hi"), Ids.newId()));
  }

  @Test
  void durabilityDisabledLeavesStoreEmpty() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var agent = new Agent(AgentConfig.newBuilder().withModel(alwaysStop("ok")).build());
    agent.run("hi");
    assertTrue(store.findByStatus(AgentRunStatus.COMPLETED).isEmpty());
    assertTrue(store.findByStatus(AgentRunStatus.RUNNING).isEmpty());
    // unused journal sanity:
    assertTrue(journal.all(Ids.newId()).isEmpty());
  }

  @Test
  void durableRunWritesTerminalCompletedCheckpoint() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(sessionId, "hi"), runId);
    assertTrue(result.isSuccess());
    var run = store.find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, run.status());
    assertNotNull(run.endedAt());
    assertNull(run.error());
  }

  @Test
  void durableRunJournalsToolCalls() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var weatherTool =
        Tool.newBuilder()
            .withName("weather")
            .withDescription("Get weather")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("sunny"))
            .build();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("weather", "call_1"))
                .withTool(weatherTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(sessionId, "hi"), runId);
    assertTrue(result.isSuccess());
    var entries = journal.all(runId);
    assertEquals(1, entries.size());
    var entry = entries.get(0);
    assertEquals(ToolCallStatus.SUCCEEDED, entry.status());
    assertEquals("sunny", entry.output());
    assertEquals("call_1", entry.toolCallId());
  }

  @Test
  void nonIdempotentToolBypassesRetry() {
    var attempts = new AtomicInteger(0);
    var failingTool =
        Tool.newBuilder()
            .withName("send")
            .withExecutor(
                (args, ctx) -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("transient");
                })
            .build();
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(
                RetryPolicy.newBuilder()
                    .withMaxAttempts(3)
                    .withBackoff(Backoff.fixed(Duration.ZERO))
                    .build())
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("send", "call_1"))
                .withTool(failingTool)
                .withIncludeMemoryTools(false)
                .withFaultTolerance(ft)
                .build());
    agent.run("hi");
    assertEquals(1, attempts.get(), "non-idempotent tool must execute exactly once");
  }

  @Test
  void resumeRequiresDurabilityConfigured() {
    var agent = new Agent(AgentConfig.newBuilder().withModel(alwaysStop("ok")).build());
    assertThrows(IllegalStateException.class, () -> agent.resume(Ids.newId()));
  }

  @Test
  void resumeUnknownRunFails() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.resume(Ids.newId());
    assertTrue(result.isFailure());
  }

  @Test
  void resumeAlreadyCompletedRunFails() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    agent.run(SessionContext.of(sessionId, "hi"), runId);
    // Run is now COMPLETED — resume must reject it.
    var result = agent.resume(runId);
    assertTrue(result.isFailure());
  }

  @Test
  void resumeFailLoudOnNonIdempotentInflight() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    var sendTool =
        Tool.newBuilder()
            .withName("send")
            .withExecutor((args, ctx) -> ToolResult.success("sent"))
            .build();
    // Plant a SUSPENDED run + non-idempotent in-flight call.
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("call_1")
            .withToolName("send")
            .withStartedAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("ok"))
                .withTool(sendTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertTrue(failure.cause() instanceof UnsafeResumeException);
    assertEquals(AgentRunStatus.SUSPENDED, store.find(runId).orElseThrow().status());
  }

  @Test
  void resumeAutoFailContinuesAfterUnsafeInflight() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    var sendTool =
        Tool.newBuilder()
            .withName("send")
            .withExecutor((args, ctx) -> ToolResult.success("sent"))
            .build();
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("call_1")
            .withToolName("send")
            .withStartedAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("recovered"))
                .withTool(sendTool)
                .withIncludeMemoryTools(false)
                .withDurability(
                    Durability.newBuilder()
                        .withRunStore(store)
                        .withToolCallJournal(journal)
                        .withUnsafeResumePolicy(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE)
                        .build())
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isSuccess(), "expected resume to recover under AUTO_FAIL_AND_CONTINUE");
    assertEquals(AgentRunStatus.COMPLETED, store.find(runId).orElseThrow().status());
    var entries = journal.all(runId);
    var inflightFailed =
        entries.stream()
            .filter(e -> e.toolCallId().equals("call_1"))
            .filter(e -> e.status() == ToolCallStatus.FAILED)
            .count();
    assertEquals(1, inflightFailed, "in-flight entry must have been transitioned to FAILED");
  }

  @Test
  void resumeIdempotentInflightContinues() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    var safeTool =
        Tool.newBuilder()
            .withName("get_x")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.RUNNING)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("call_1")
            .withToolName("get_x")
            .withStartedAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("recovered"))
                .withTool(safeTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isSuccess());
    assertEquals(AgentRunStatus.COMPLETED, store.find(runId).orElseThrow().status());
  }

  @Test
  void resumeRequiresMemory() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isFailure());
  }

  @Test
  void resumeRejectsNullArguments() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    assertTrue(agent.resume((java.util.UUID) null).isFailure());
    // Null promptVars is tolerated — treated as Map.of() — so the run-not-found path is what
    // surfaces the failure here.
    assertTrue(agent.resume(Ids.newId(), (java.util.Map<String, String>) null).isFailure());
  }

  @Test
  void deployerOverrideMakesToolNonIdempotent() {
    var attempts = new AtomicInteger(0);
    var tool =
        Tool.newBuilder()
            .withName("get_x")
            .withIdempotent(true)
            .withExecutor(
                (args, ctx) -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("transient");
                })
            .build();
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(
                RetryPolicy.newBuilder()
                    .withMaxAttempts(3)
                    .withBackoff(Backoff.fixed(Duration.ZERO))
                    .build())
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("get_x", "call_1"))
                .withTool(tool)
                .withIncludeMemoryTools(false)
                .withFaultTolerance(ft)
                .withDurability(
                    Durability.newBuilder()
                        .withRunStore(new InMemoryRunStore())
                        .withToolCallJournal(new InMemoryToolCallJournal())
                        .withIdempotentToolOverride("get_x", false)
                        .build())
                .build());
    agent.run("hi");
    assertEquals(1, attempts.get(), "deployer override must override the tool's own flag");
  }

  @Test
  void faultToleranceWithoutRetryReturnsSelfWhenNoRetryPolicy() {
    var ft = FaultTolerance.PASSTHROUGH;
    assertEquals(ft, ft.withoutRetry());
  }

  @Test
  void faultToleranceWithoutRetryStripsRetryButKeepsRest() throws Exception {
    var attempts = new AtomicInteger(0);
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(
                RetryPolicy.newBuilder()
                    .withMaxAttempts(3)
                    .withBackoff(Backoff.fixed(Duration.ZERO))
                    .build())
            .build();
    var stripped = ft.withoutRetry();
    assertNull(stripped.retryPolicy());
    assertThrows(
        RuntimeException.class,
        () ->
            stripped.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("boom");
                }));
    assertEquals(1, attempts.get());
  }

  @Test
  void durabilityFailedStepWritesFailedCheckpoint() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var failingModel =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("model down");
          }

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(failingModel)
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(sessionId, "hi"), runId);
    assertFalse(result.isSuccess());
    var run = store.find(runId).orElseThrow();
    assertEquals(AgentRunStatus.FAILED, run.status());
    assertNotNull(run.error());
    assertNotNull(run.endedAt());
  }

  @Test
  void runIdGeneratedWhenNotProvided() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    agent.run("hi");
    assertEquals(1, store.findByStatus(AgentRunStatus.COMPLETED).size());
  }

  @Test
  void explicitRunIdPreserved() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var runId = UUID.fromString("00000000-0000-7000-8000-000000000001");
    var sessionId = Ids.newId();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    agent.run(SessionContext.of(sessionId, "hi"), runId);
    assertNotNull(store.find(runId).orElse(null));
  }

  // --- Durability error-path coverage: failures in the store/journal must not abort the run ---

  /** {@link RunStore} that always throws — exercises the {@code safeCheckpoint} catch path. */
  private static final class ThrowingRunStore implements ai.singlr.core.runtime.RunStore {
    @Override
    public void checkpoint(ai.singlr.core.runtime.AgentRun run) {
      throw new RuntimeException("simulated checkpoint failure");
    }

    @Override
    public java.util.Optional<ai.singlr.core.runtime.AgentRun> find(UUID runId) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.List<ai.singlr.core.runtime.AgentRun> findByStatus(AgentRunStatus status) {
      return java.util.List.of();
    }

    @Override
    public int purgeOlderThan(java.time.Duration olderThan) {
      return 0;
    }
  }

  /** {@link ai.singlr.core.runtime.ToolCallJournal} that always throws on every method. */
  private static final class ThrowingJournal implements ai.singlr.core.runtime.ToolCallJournal {
    @Override
    public void start(ToolCallRecord record) {
      throw new RuntimeException("simulated journal start failure");
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      throw new RuntimeException("simulated journal complete failure");
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      throw new RuntimeException("simulated journal fail failure");
    }

    @Override
    public java.util.List<ToolCallRecord> inflight(UUID runId) {
      return java.util.List.of();
    }

    @Override
    public java.util.List<ToolCallRecord> all(UUID runId) {
      return java.util.List.of();
    }
  }

  @Test
  void runStoreFailureDoesNotAbortRun() {
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(
                    Durability.of(new ThrowingRunStore(), new InMemoryToolCallJournal()))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(Ids.newId(), "hi"), Ids.newId());
    assertTrue(result.isSuccess(), "RunStore failures must be best-effort, not run-fatal");
  }

  @Test
  void journalStartFailureDoesNotAbortRun() {
    var weatherTool =
        Tool.newBuilder()
            .withName("weather")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("sunny"))
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("weather", "call_1"))
                .withTool(weatherTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(new InMemoryRunStore(), new ThrowingJournal()))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(Ids.newId(), "hi"), Ids.newId());
    assertTrue(result.isSuccess(), "journal failures must be best-effort, not run-fatal");
  }

  /**
   * Journal that succeeds on {@code start} (so the matching terminal write is required) but throws
   * on {@code complete}/{@code fail}. Exercises the new {@code journalTerminal} catch path: the
   * tool result must still be returned to the caller.
   */
  private static final class JournalThatFailsTerminal
      implements ai.singlr.core.runtime.ToolCallJournal {
    private final java.util.List<ToolCallRecord> records =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    public void start(ToolCallRecord record) {
      records.add(record);
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {
      throw new RuntimeException("simulated complete failure");
    }

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      throw new RuntimeException("simulated fail failure");
    }

    @Override
    public java.util.List<ToolCallRecord> inflight(UUID runId) {
      return java.util.List.copyOf(records);
    }

    @Override
    public java.util.List<ToolCallRecord> all(UUID runId) {
      return java.util.List.copyOf(records);
    }
  }

  @Test
  void journalTerminalSuccessFailureDoesNotAbortRun() {
    var weatherTool =
        Tool.newBuilder()
            .withName("weather")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("sunny"))
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("weather", "call_1"))
                .withTool(weatherTool)
                .withIncludeMemoryTools(false)
                .withDurability(
                    Durability.of(new InMemoryRunStore(), new JournalThatFailsTerminal()))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(Ids.newId(), "hi"), Ids.newId());
    assertTrue(result.isSuccess(), "journal terminal-write failure must not abort the run");
  }

  @Test
  void journalTerminalFailureFailureDoesNotAbortRun() {
    var failingTool =
        Tool.newBuilder()
            .withName("send")
            .withExecutor((args, ctx) -> ToolResult.failure("downstream timeout"))
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStop("send", "call_1"))
                .withTool(failingTool)
                .withIncludeMemoryTools(false)
                .withDurability(
                    Durability.of(new InMemoryRunStore(), new JournalThatFailsTerminal()))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(Ids.newId(), "hi"), Ids.newId());
    assertTrue(
        result.isSuccess(),
        "journal failure-write failure on a tool that itself failed must not abort the run");
  }

  // --- Resume safety: agentId mismatch ---

  @Test
  void resumeAgentIdMismatchFails() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("research-bot")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("billing-bot") // different agent than the run was started under
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isFailure());
    assertTrue(((Result.Failure<Response>) result).error().contains("research-bot"));
  }

  /**
   * A journal whose {@code fail} throws — used to cover the inner catch in {@code prepareResume}
   * that handles "failed to mark in-flight entry for retry/recovery."
   */
  private static final class ResumeMarkingJournal
      implements ai.singlr.core.runtime.ToolCallJournal {
    private final java.util.List<ToolCallRecord> records;

    ResumeMarkingJournal(java.util.List<ToolCallRecord> seed) {
      this.records = new java.util.ArrayList<>(seed);
    }

    @Override
    public void start(ToolCallRecord record) {
      records.add(record);
    }

    @Override
    public void complete(UUID runId, String toolCallId, String output) {}

    @Override
    public void fail(UUID runId, String toolCallId, String error) {
      throw new RuntimeException("simulated mark-failed failure");
    }

    @Override
    public java.util.List<ToolCallRecord> inflight(UUID runId) {
      return java.util.List.copyOf(records);
    }

    @Override
    public java.util.List<ToolCallRecord> all(UUID runId) {
      return java.util.List.copyOf(records);
    }
  }

  @Test
  void resumeMarkFailedExceptionLoggedNotPropagated() {
    var store = new InMemoryRunStore();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    var idempotentTool =
        Tool.newBuilder()
            .withName("get_x")
            .withIdempotent(true)
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var seedRecord =
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("call_1")
            .withToolName("get_x")
            .withStartedAt(Ids.now())
            .build();
    var journal = new ResumeMarkingJournal(java.util.List.of(seedRecord));
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("recovered"))
                .withTool(idempotentTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isSuccess(), "mark-failed exception during resume must be swallowed");
  }

  /**
   * A run-store whose {@code checkpoint} throws after a successful {@code find}. Used to drive the
   * {@code safeCheckpoint} catch in {@code prepareResume} when transitioning a run to SUSPENDED
   * under FAIL_LOUD policy.
   */
  private static final class FindOkCheckpointThrowsStore
      implements ai.singlr.core.runtime.RunStore {
    private final ai.singlr.core.runtime.AgentRun seeded;

    FindOkCheckpointThrowsStore(ai.singlr.core.runtime.AgentRun seeded) {
      this.seeded = seeded;
    }

    @Override
    public void checkpoint(ai.singlr.core.runtime.AgentRun run) {
      throw new RuntimeException("simulated checkpoint failure");
    }

    @Override
    public java.util.Optional<ai.singlr.core.runtime.AgentRun> find(UUID runId) {
      return seeded.runId().equals(runId)
          ? java.util.Optional.of(seeded)
          : java.util.Optional.empty();
    }

    @Override
    public java.util.List<ai.singlr.core.runtime.AgentRun> findByStatus(AgentRunStatus status) {
      return java.util.List.of();
    }

    @Override
    public int purgeOlderThan(java.time.Duration olderThan) {
      return 0;
    }
  }

  // --- Typed (structured-output) overloads ---

  /** Schema with a single field used to verify the typed durable {@code run} and {@code resume}. */
  public record SimpleAnswer(String value) {}

  @Test
  void typedDurableRunRoundtrip() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var schema = ai.singlr.core.schema.OutputSchema.of(SimpleAnswer.class);
    var typedModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, ai.singlr.core.schema.OutputSchema<T> os) {
            var parsed = os.type().cast(new SimpleAnswer("hello"));
            return Response.<T>newBuilder(os.type())
                .withContent("{\"value\":\"hello\"}")
                .withFinishReason(FinishReason.STOP)
                .withParsed(parsed)
                .build();
          }

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(typedModel)
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(sessionId, "hi"), runId, schema);
    assertTrue(result.isSuccess());
    assertEquals(
        "hello", ((Result.Success<Response<SimpleAnswer>>) result).value().parsed().value());
    assertEquals(AgentRunStatus.COMPLETED, store.find(runId).orElseThrow().status());
  }

  @Test
  void typedResumeRoundtrip() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    store.checkpoint(
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build());
    var schema = ai.singlr.core.schema.OutputSchema.of(SimpleAnswer.class);
    var typedModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, ai.singlr.core.schema.OutputSchema<T> os) {
            var parsed = os.type().cast(new SimpleAnswer("recovered"));
            return Response.<T>newBuilder(os.type())
                .withContent("{\"value\":\"recovered\"}")
                .withFinishReason(FinishReason.STOP)
                .withParsed(parsed)
                .build();
          }

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(typedModel)
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId, schema);
    assertTrue(result.isSuccess());
    assertEquals(
        "recovered", ((Result.Success<Response<SimpleAnswer>>) result).value().parsed().value());
  }

  @Test
  void typedResumeFailureCarriesFailure() {
    var store = new InMemoryRunStore();
    var journal = new InMemoryToolCallJournal();
    var schema = ai.singlr.core.schema.OutputSchema.of(SimpleAnswer.class);
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(alwaysStop("ok"))
                .withDurability(Durability.of(store, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.resume(Ids.newId(), schema);
    assertTrue(result.isFailure());
  }

  @Test
  void typedRunRequiresDurability() {
    var schema = ai.singlr.core.schema.OutputSchema.of(SimpleAnswer.class);
    var agent = new Agent(AgentConfig.newBuilder().withModel(alwaysStop("ok")).build());
    assertThrows(
        IllegalStateException.class, () -> agent.run(SessionContext.of("hi"), Ids.newId(), schema));
  }

  @Test
  void resumeFailLoudCheckpointFailureStillSurfacesUnsafeException() {
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();
    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("hi"));
    var sendTool =
        Tool.newBuilder()
            .withName("send")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    var seeded =
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.SUSPENDED)
            .withIteration(0)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build();
    var store = new FindOkCheckpointThrowsStore(seeded);
    var journal = new InMemoryToolCallJournal();
    journal.start(
        ToolCallRecord.newBuilder()
            .withRunId(runId)
            .withIteration(0)
            .withToolCallId("call_1")
            .withToolName("send")
            .withStartedAt(Ids.now())
            .build());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("ok"))
                .withTool(sendTool)
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(store, journal))
                .withMemory(memory)
                .build());
    var result = agent.resume(runId);
    assertTrue(result.isFailure());
    assertTrue(((Result.Failure<Response>) result).cause() instanceof UnsafeResumeException);
  }

  // --- End-to-end checkpointFrequency + resume integration ----------------------------------

  /**
   * Coordinated test for the {@link Durability#checkpointFrequency()} knob, exercised through the
   * full {@code Agent.run} → {@code Agent.resume} lifecycle. Two assertions in one flow:
   *
   * <ol>
   *   <li><b>Skip behavior.</b> Non-aligned iterations do not write RUNNING checkpoints. A
   *       4-iteration run with frequency=3 must produce at most 2 RUNNING writes (iter 0 from
   *       initialize + the resumed step's iter-0 top, plus iter 3) — strictly fewer than the 4
   *       you'd see at frequency=1.
   *   <li><b>Resume picks up at the planted iteration.</b> When a checkpoint at an aligned
   *       iteration N is the last surviving record, {@code agent.resume(runId)} reconstitutes state
   *       with {@code iterations=N} and the next step's checkpoint reflects that — proving the
   *       iteration count survived the planted checkpoint round-trip rather than being reset to 0.
   * </ol>
   */
  @Test
  void resumeRehydratesIterationFromAlignedCheckpoint() {
    var capturing = new CapturingRunStore();
    var journal = new InMemoryToolCallJournal();
    var memory = InMemoryMemory.withDefaults();
    var runId = Ids.newId();
    var sessionId = Ids.newId();

    memory.registerSession("u", sessionId);
    memory.addMessage("u", sessionId, Message.user("original prompt"));
    memory.addMessage("u", sessionId, Message.assistant("turn 1 partial work"));
    memory.addMessage("u", sessionId, Message.user("more"));
    memory.addMessage("u", sessionId, Message.assistant("turn 2 partial work"));

    // Plant: a RUNNING checkpoint at the last frequency-aligned iteration before the simulated
    // crash. Simulates: agent crashed sometime after writing this checkpoint but before the
    // next aligned write at iter 6.
    var planted =
        ai.singlr.core.runtime.AgentRun.newBuilder()
            .withRunId(runId)
            .withSessionId(sessionId)
            .withUserId("u")
            .withAgentId("test")
            .withStatus(AgentRunStatus.RUNNING)
            .withIteration(3)
            .withStartedAt(Ids.now())
            .withLastCheckpointAt(Ids.now())
            .build();
    capturing.checkpoint(planted);
    capturing.reset(); // drop the planting write from the capture so we measure only resume traffic

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(alwaysStop("resumed completion"))
                .withDurability(
                    Durability.newBuilder()
                        .withRunStore(capturing)
                        .withToolCallJournal(journal)
                        .withCheckpointFrequency(3)
                        .build())
                .withMemory(memory)
                .build());

    var result = agent.resume(runId);

    assertTrue(result.isSuccess(), "resume from aligned checkpoint must succeed");
    var finalRun = capturing.find(runId).orElseThrow();
    assertEquals(AgentRunStatus.COMPLETED, finalRun.status());
    // The model produced STOP on its first call, so iterations advanced from 3 → 4 (one model
    // turn after resume). A fresh run would have ended at iteration 1.
    assertEquals(4, finalRun.iteration(), "resume must preserve planted iteration count");

    // Frequency-gate observable: every RUNNING write landed at iter=3 (the planted-checkpoint
    // refresh during prepareResume + the top-of-step checkpoint inside the resumed loop). NONE
    // landed at iter=4 (the post-model-turn state) because 4 % 3 != 0. The final COMPLETED
    // write is unconditional and carries iter=4. With frequency=1, iter=4 would have produced an
    // additional RUNNING write before the terminal one.
    var runningIterations =
        capturing.captured().stream()
            .filter(r -> r.status() == AgentRunStatus.RUNNING)
            .map(ai.singlr.core.runtime.AgentRun::iteration)
            .toList();
    var completedWrites =
        capturing.captured().stream().filter(r -> r.status() == AgentRunStatus.COMPLETED).toList();
    assertTrue(
        runningIterations.stream().allMatch(it -> it == 3),
        "frequency=3 must skip iter 4 RUNNING write; saw iterations " + runningIterations);
    assertFalse(
        runningIterations.contains(4),
        "iter=4 is not aligned and must not produce a RUNNING write");
    assertEquals(1, completedWrites.size(), "terminal COMPLETED write is unconditional");
    assertEquals(4, completedWrites.getFirst().iteration());
  }

  /**
   * Negative control: a fresh durable run with default frequency (=1) writes a RUNNING checkpoint
   * per step. Pairs with the resume test above to confirm the knob is the only variable controlling
   * write frequency.
   */
  @Test
  void defaultCheckpointFrequencyWritesEveryIteration() {
    var capturing = new CapturingRunStore();
    var journal = new InMemoryToolCallJournal();
    var runId = Ids.newId();
    var sessionId = Ids.newId();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("test")
                .withModel(toolThenStop("weather", "call_1"))
                .withTool(
                    Tool.newBuilder()
                        .withName("weather")
                        .withIdempotent(true)
                        .withExecutor((args, ctx) -> ToolResult.success("sunny"))
                        .build())
                .withIncludeMemoryTools(false)
                .withDurability(Durability.of(capturing, journal))
                .withMemory(InMemoryMemory.withDefaults())
                .build());
    var result = agent.run(SessionContext.of(sessionId, "hi"), runId);
    assertTrue(result.isSuccess());

    var runningWrites =
        capturing.captured().stream().filter(r -> r.status() == AgentRunStatus.RUNNING).count();
    assertTrue(
        runningWrites >= 2,
        "default frequency=1 must write RUNNING for every step iteration (tool turn + stop"
            + " turn); saw "
            + runningWrites);
  }

  /**
   * Captures every {@code checkpoint} call so tests can assert how many RUNNING / COMPLETED rows
   * land, and at which iteration. Reads pass through to a backing {@link InMemoryRunStore}.
   */
  private static final class CapturingRunStore implements ai.singlr.core.runtime.RunStore {
    private final InMemoryRunStore delegate = new InMemoryRunStore();
    private final java.util.List<ai.singlr.core.runtime.AgentRun> captured =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    java.util.List<ai.singlr.core.runtime.AgentRun> captured() {
      return java.util.List.copyOf(captured);
    }

    void reset() {
      captured.clear();
    }

    @Override
    public void checkpoint(ai.singlr.core.runtime.AgentRun run) {
      captured.add(run);
      delegate.checkpoint(run);
    }

    @Override
    public java.util.Optional<ai.singlr.core.runtime.AgentRun> find(UUID rid) {
      return delegate.find(rid);
    }

    @Override
    public List<ai.singlr.core.runtime.AgentRun> findByStatus(AgentRunStatus status) {
      return delegate.findByStatus(status);
    }

    @Override
    public int purgeOlderThan(java.time.Duration olderThan) {
      return delegate.purgeOlderThan(olderThan);
    }
  }
}
